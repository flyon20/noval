import {
  MIN_TEAR_SPEED,
  XPBD_FIXED_STEP_SECONDS,
  SilkWebTopology,
  SilkWebTopologyBuilder,
  clampValue,
  resolveElasticMeshProfile,
  type ElasticMeshProfile,
  type ElasticMeshProfileName,
  type MeshEdgeKind,
  type SilkFiber,
} from './elastic-mesh';

export interface XpbdConstraint {
  resetStep(): void;
  solve(particles: ElasticParticle[], stepSeconds: number, now: number): void;
}

export class PointerInteractor {
  x = 0;
  y = 0;
  speed = 0;
  velocityX = 0;
  velocityY = 0;
  active = false;
  pressed = false;

  private previousX = 0;
  private previousY = 0;
  private previousTime = 0;

  move(x: number, y: number, time: number, active: boolean) {
    const elapsed = this.active && active && this.previousTime > 0
      ? Math.max(8, time - this.previousTime)
      : 0;
    this.velocityX = elapsed > 0 ? ((x - this.previousX) / elapsed) * 1000 : 0;
    this.velocityY = elapsed > 0 ? ((y - this.previousY) / elapsed) * 1000 : 0;
    this.speed = Math.hypot(this.velocityX, this.velocityY) / 1000;
    this.x = x;
    this.y = y;
    this.active = active;
    this.previousX = x;
    this.previousY = y;
    this.previousTime = time;
    if (!active) {
      this.pressed = false;
    }
  }

  release() {
    this.pressed = false;
  }
}

export class ElasticParticle {
  previousX: number;
  previousY: number;

  constructor(
    public x: number,
    public y: number,
    public readonly originX: number,
    public readonly originY: number,
    public readonly inverseMass: number,
    public readonly row = 0,
    public readonly column = 0,
  ) {
    this.previousX = x;
    this.previousY = y;
  }

  get pinned() {
    return this.inverseMass === 0;
  }

  integrate(stepSeconds: number, time: number, profile: ElasticMeshProfile) {
    if (this.pinned) {
      this.x = this.originX;
      this.y = this.originY;
      this.previousX = this.originX;
      this.previousY = this.originY;
      return;
    }
    const velocityX = (this.x - this.previousX) * profile.damping;
    const velocityY = (this.y - this.previousY) * profile.damping;
    this.previousX = this.x;
    this.previousY = this.y;
    const phase = this.row * 0.61 + this.column * 0.37;
    const accelerationX = Math.sin(time * 0.00034 + phase) * 5.5;
    const accelerationY = Math.cos(time * 0.00029 - phase) * 4.5;
    const stepSquared = stepSeconds * stepSeconds;
    this.x += velocityX + accelerationX * stepSquared * this.inverseMass;
    this.y += velocityY + accelerationY * stepSquared * this.inverseMass;
  }

  velocityX(stepSeconds: number) {
    return (this.x - this.previousX) / stepSeconds;
  }

  velocityY(stepSeconds: number) {
    return (this.y - this.previousY) / stepSeconds;
  }

  addVelocity(velocityX: number, velocityY: number, stepSeconds: number) {
    if (!this.pinned) {
      this.previousX -= velocityX * stepSeconds;
      this.previousY -= velocityY * stepSeconds;
    }
  }
}

export class DistanceConstraint implements XpbdConstraint {
  lambda = 0;
  active = true;
  brokenAt = 0;
  peakStrain = 0;

  constructor(
    public readonly first: number,
    public readonly second: number,
    public readonly restLength: number,
    public readonly kind: MeshEdgeKind | 'adhesion' = 'warp',
    public readonly compliance = 0.000035,
    public readonly breakable = true,
  ) {}

  isBroken() {
    return !this.active;
  }

  break(now = 0) {
    if (!this.breakable) {
      return false;
    }
    this.active = false;
    this.brokenAt = now;
    this.lambda = 0;
    return true;
  }

  resetStep() {
    this.lambda = 0;
    this.peakStrain = 0;
  }

  solve(
    particles: ElasticParticle[],
    stepSeconds: number,
    _now = 0,
    compliance = this.compliance,
  ) {
    if (!this.active) {
      return;
    }
    const first = particles[this.first];
    const second = particles[this.second];
    const deltaX = second.x - first.x;
    const deltaY = second.y - first.y;
    const distance = Math.max(0.001, Math.hypot(deltaX, deltaY));
    this.peakStrain = Math.max(this.peakStrain, Math.abs(distance / this.restLength - 1));
    const weightSum = first.inverseMass + second.inverseMass;
    if (weightSum === 0) {
      return;
    }
    const alpha = compliance / (stepSeconds * stepSeconds);
    const constraintError = distance - this.restLength;
    const deltaLambda = (-constraintError - alpha * this.lambda) / (weightSum + alpha);
    this.lambda += deltaLambda;
    const normalX = deltaX / distance;
    const normalY = deltaY / distance;
    first.x -= first.inverseMass * normalX * deltaLambda;
    first.y -= first.inverseMass * normalY * deltaLambda;
    second.x += second.inverseMass * normalX * deltaLambda;
    second.y += second.inverseMass * normalY * deltaLambda;
  }

  currentLength(particles: ElasticParticle[]) {
    const first = particles[this.first];
    const second = particles[this.second];
    return Math.hypot(second.x - first.x, second.y - first.y);
  }

  strain(particles: ElasticParticle[]) {
    return this.currentLength(particles) / Math.max(0.001, this.restLength) - 1;
  }

  tension(particles: ElasticParticle[]) {
    return clampValue(Math.abs(this.strain(particles)) / 0.42, 0, 1);
  }

  midpointDistance(particles: ElasticParticle[], x: number, y: number) {
    const first = particles[this.first];
    const second = particles[this.second];
    return Math.hypot((first.x + second.x) * 0.5 - x, (first.y + second.y) * 0.5 - y);
  }

  distanceToPoint(particles: ElasticParticle[], x: number, y: number) {
    const first = particles[this.first];
    const second = particles[this.second];
    const edgeX = second.x - first.x;
    const edgeY = second.y - first.y;
    const lengthSquared = edgeX * edgeX + edgeY * edgeY;
    if (lengthSquared <= 0.0001) {
      return Math.hypot(x - first.x, y - first.y);
    }
    const projection = clampValue(
      ((x - first.x) * edgeX + (y - first.y) * edgeY) / lengthSquared,
      0,
      1,
    );
    return Math.hypot(
      x - (first.x + edgeX * projection),
      y - (first.y + edgeY * projection),
    );
  }
}

export class BendingConstraint implements XpbdConstraint {
  lambda = 0;

  constructor(
    public readonly first: number,
    public readonly middle: number,
    public readonly second: number,
    public readonly restCosine: number,
    public readonly compliance: number,
    private readonly dependencies: readonly DistanceConstraint[] = [],
  ) {}

  resetStep() {
    this.lambda = 0;
  }

  solve(particles: ElasticParticle[], stepSeconds: number, _now = 0) {
    if (this.dependencies.some((constraint) => constraint.isBroken())) {
      return;
    }
    const first = particles[this.first];
    const middle = particles[this.middle];
    const second = particles[this.second];
    const firstX = first.x - middle.x;
    const firstY = first.y - middle.y;
    const secondX = second.x - middle.x;
    const secondY = second.y - middle.y;
    const firstLength = Math.max(0.001, Math.hypot(firstX, firstY));
    const secondLength = Math.max(0.001, Math.hypot(secondX, secondY));
    const firstNormalX = firstX / firstLength;
    const firstNormalY = firstY / firstLength;
    const secondNormalX = secondX / secondLength;
    const secondNormalY = secondY / secondLength;
    const cosine = clampValue(
      firstNormalX * secondNormalX + firstNormalY * secondNormalY,
      -1,
      1,
    );
    const firstGradientX = (secondNormalX - firstNormalX * cosine) / firstLength;
    const firstGradientY = (secondNormalY - firstNormalY * cosine) / firstLength;
    const secondGradientX = (firstNormalX - secondNormalX * cosine) / secondLength;
    const secondGradientY = (firstNormalY - secondNormalY * cosine) / secondLength;
    const middleGradientX = -firstGradientX - secondGradientX;
    const middleGradientY = -firstGradientY - secondGradientY;
    const weightSum = first.inverseMass * (firstGradientX ** 2 + firstGradientY ** 2)
      + middle.inverseMass * (middleGradientX ** 2 + middleGradientY ** 2)
      + second.inverseMass * (secondGradientX ** 2 + secondGradientY ** 2);
    if (weightSum <= 0.000001) {
      return;
    }
    const alpha = this.compliance / (stepSeconds * stepSeconds);
    const deltaLambda = (-(cosine - this.restCosine) - alpha * this.lambda) / (weightSum + alpha);
    this.lambda += deltaLambda;
    first.x += first.inverseMass * firstGradientX * deltaLambda;
    first.y += first.inverseMass * firstGradientY * deltaLambda;
    middle.x += middle.inverseMass * middleGradientX * deltaLambda;
    middle.y += middle.inverseMass * middleGradientY * deltaLambda;
    second.x += second.inverseMass * secondGradientX * deltaLambda;
    second.y += second.inverseMass * secondGradientY * deltaLambda;
  }
}

export class RestShapeConstraint implements XpbdConstraint {
  private readonly lambdaX: number[];
  private readonly lambdaY: number[];

  constructor(private readonly compliance: number) {
    this.lambdaX = [];
    this.lambdaY = [];
  }

  resetStep() {
    this.lambdaX.fill(0);
    this.lambdaY.fill(0);
  }

  solve(particles: ElasticParticle[], stepSeconds: number, _now = 0) {
    const alpha = this.compliance / (stepSeconds * stepSeconds);
    for (const [index, particle] of particles.entries()) {
      if (particle.pinned) {
        continue;
      }
      this.lambdaX[index] ??= 0;
      this.lambdaY[index] ??= 0;
      const denominator = particle.inverseMass + alpha;
      const deltaLambdaX = (-(particle.x - particle.originX) - alpha * this.lambdaX[index]) / denominator;
      const deltaLambdaY = (-(particle.y - particle.originY) - alpha * this.lambdaY[index]) / denominator;
      this.lambdaX[index] += deltaLambdaX;
      this.lambdaY[index] += deltaLambdaY;
      particle.x += particle.inverseMass * deltaLambdaX;
      particle.y += particle.inverseMass * deltaLambdaY;
    }
  }
}

interface ContactBinding {
  readonly particleIndex: number;
  readonly offsetX: number;
  readonly offsetY: number;
  readonly weight: number;
  lambdaX: number;
  lambdaY: number;
}

export class PointerContactPatch implements XpbdConstraint {
  readonly bindings: ContactBinding[];
  targetX: number;
  targetY: number;

  private constructor(
    bindings: ContactBinding[],
    private readonly compliance: number,
    public readonly originX: number,
    public readonly originY: number,
  ) {
    this.bindings = bindings;
    this.targetX = originX;
    this.targetY = originY;
  }

  static capture(
    particles: ElasticParticle[],
    pointer: PointerInteractor,
    profile: ElasticMeshProfile,
  ) {
    const candidates = particles
      .map((particle, particleIndex) => ({
        particle,
        particleIndex,
        distance: Math.hypot(particle.x - pointer.x, particle.y - pointer.y),
      }))
      .filter(({ particle, distance }) => !particle.pinned && distance < profile.pointerContactRadius)
      .sort((first, second) => first.distance - second.distance)
      .slice(0, profile.pointerContactLimit);
    const bindings = candidates.map(({ particle, particleIndex, distance }) => ({
      particleIndex,
      offsetX: particle.x - pointer.x,
      offsetY: particle.y - pointer.y,
      weight: Math.max(0.12, (1 - distance / profile.pointerContactRadius) ** 2),
      lambdaX: 0,
      lambdaY: 0,
    }));
    return bindings.length > 0
      ? new PointerContactPatch(bindings, profile.pointerCompliance, pointer.x, pointer.y)
      : null;
  }

  get indices() {
    return this.bindings.map((binding) => binding.particleIndex);
  }

  get pullDistance() {
    return Math.hypot(this.targetX - this.originX, this.targetY - this.originY);
  }

  setTarget(x: number, y: number) {
    this.targetX = x;
    this.targetY = y;
  }

  resetStep() {
    for (const binding of this.bindings) {
      binding.lambdaX = 0;
      binding.lambdaY = 0;
    }
  }

  solve(particles: ElasticParticle[], stepSeconds: number) {
    for (const binding of this.bindings) {
      const particle = particles[binding.particleIndex];
      if (!particle || particle.pinned) {
        continue;
      }
      const targetX = this.targetX + binding.offsetX * 0.82;
      const targetY = this.targetY + binding.offsetY * 0.82;
      const alpha = (this.compliance / binding.weight) / (stepSeconds * stepSeconds);
      const denominator = particle.inverseMass + alpha;
      const deltaLambdaX = (-(particle.x - targetX) - alpha * binding.lambdaX) / denominator;
      const deltaLambdaY = (-(particle.y - targetY) - alpha * binding.lambdaY) / denominator;
      binding.lambdaX += deltaLambdaX;
      binding.lambdaY += deltaLambdaY;
      particle.x += particle.inverseMass * deltaLambdaX * binding.weight;
      particle.y += particle.inverseMass * deltaLambdaY * binding.weight;
    }
  }
}

export class PointerPressureField {
  lastAffectedCount = 0;

  apply(
    particles: ElasticParticle[],
    pointer: PointerInteractor,
    profile: ElasticMeshProfile,
    stepSeconds: number,
  ) {
    this.lastAffectedCount = 0;
    if (!pointer.active) {
      return;
    }
    for (const particle of particles) {
      if (particle.pinned) {
        continue;
      }
      const deltaX = particle.x - pointer.x;
      const deltaY = particle.y - pointer.y;
      const distance = Math.max(1, Math.hypot(deltaX, deltaY));
      if (distance >= profile.pointerHoverRadius) {
        continue;
      }
      const falloff = (1 - distance / profile.pointerHoverRadius) ** 2;
      const pressure = profile.pointerPressureStrength * falloff;
      const follow = pointer.pressed ? 0.045 : 0.022;
      particle.addVelocity(
        pointer.velocityX * follow * falloff,
        pointer.velocityY * follow * falloff,
        stepSeconds,
      );
      const stepSquared = stepSeconds * stepSeconds;
      particle.x += (deltaX / distance) * pressure * stepSquared;
      particle.y += (deltaY / distance) * pressure * stepSquared;
      this.lastAffectedCount += 1;
    }
  }
}

export class WaveImpulse {
  constructor(
    public readonly x: number,
    public readonly y: number,
    public readonly amplitude: number,
    public readonly createdAt: number,
  ) {}
}

export class WaveField {
  readonly impulses: WaveImpulse[] = [];

  emit(x: number, y: number, amplitude: number, now: number) {
    this.impulses.push(new WaveImpulse(x, y, amplitude, now));
    if (this.impulses.length > 4) {
      this.impulses.shift();
    }
  }

  apply(
    particles: ElasticParticle[],
    profile: ElasticMeshProfile,
    now: number,
    stepSeconds: number,
  ) {
    for (let index = this.impulses.length - 1; index >= 0; index -= 1) {
      const impulse = this.impulses[index];
      const ageSeconds = Math.max(0, (now - impulse.createdAt) / 1000);
      const front = ageSeconds * profile.waveSpeed;
      if (front > profile.waveRadius) {
        this.impulses.splice(index, 1);
        continue;
      }
      const envelope = 1 - front / profile.waveRadius;
      for (const particle of particles) {
        if (particle.pinned) {
          continue;
        }
        const deltaX = particle.x - impulse.x;
        const deltaY = particle.y - impulse.y;
        const distance = Math.max(1, Math.hypot(deltaX, deltaY));
        const band = Math.abs(distance - front);
        if (band > 54) {
          continue;
        }
        const wave = Math.cos((band / 54) * Math.PI * 0.5) * envelope * impulse.amplitude;
        particle.addVelocity(
          (deltaX / distance) * wave,
          (deltaY / distance) * wave,
          stepSeconds,
        );
      }
    }
  }
}

export class AdhesionConstraint extends DistanceConstraint {
  constructor(
    first: number,
    second: number,
    restLength: number,
    public readonly createdAt: number,
    private readonly profile: ElasticMeshProfile,
  ) {
    super(first, second, restLength, 'adhesion', profile.adhesionStartCompliance, true);
  }

  maturity(now: number) {
    return clampValue((now - this.createdAt) / this.profile.adhesionCureDuration, 0, 1);
  }

  solve(particles: ElasticParticle[], stepSeconds: number, now = 0) {
    const maturity = this.maturity(now);
    const compliance = this.profile.adhesionStartCompliance
      + (this.profile.adhesionEndCompliance - this.profile.adhesionStartCompliance) * maturity;
    super.solve(particles, stepSeconds, now, compliance);
  }

  shouldBreak(particles: ElasticParticle[], now: number) {
    const breakLength = Math.max(this.restLength + 8, this.restLength * this.profile.adhesionBreakRatio);
    return this.maturity(now) >= 0.22 && this.currentLength(particles) > breakLength;
  }
}

export class TornConnection {
  constructor(
    public readonly source: DistanceConstraint,
    public readonly brokenAt: number,
  ) {}
}

export class TearFragment {
  readonly expiresAt: number;

  constructor(
    public readonly first: number,
    public readonly second: number,
    public readonly breakX: number,
    public readonly breakY: number,
    public readonly createdAt: number,
  ) {
    this.expiresAt = createdAt + 1100;
  }

  opacity(now: number) {
    return clampValue((this.expiresAt - now) / (this.expiresAt - this.createdAt), 0, 1);
  }
}

interface LooseEndpointState {
  readyAt: number;
}

function pairKey(first: number, second: number) {
  return first < second ? `${first}:${second}` : `${second}:${first}`;
}

export class AdhesionManager {
  readonly connections: TornConnection[] = [];
  readonly adhesions: AdhesionConstraint[] = [];
  readonly fragments: TearFragment[] = [];

  private readonly looseEndpoints = new Map<number, LooseEndpointState>();
  private readonly permanentlyBrokenPairs = new Set<string>();
  private readonly structuralConstraintByPair: ReadonlyMap<string, DistanceConstraint>;
  private nextCaptureScanAt = 0;

  constructor(
    private readonly profile: ElasticMeshProfile,
    private readonly structuralConstraints: readonly DistanceConstraint[] = [],
  ) {
    this.structuralConstraintByPair = new Map(
      structuralConstraints.map((constraint) => [
        pairKey(constraint.first, constraint.second),
        constraint,
      ] as const),
    );
  }

  get activeConstraints() {
    return this.adhesions.filter((constraint) => constraint.active);
  }

  isOpenEndpoint(index: number) {
    return this.looseEndpoints.has(index);
  }

  register(
    constraint: DistanceConstraint,
    particles: ElasticParticle[],
    now: number,
    breakX: number,
    breakY: number,
  ) {
    if (this.connections.length >= this.profile.maxTornConnections
      || this.connections.some((connection) => connection.source === constraint)
      || !constraint.break(now)) {
      return false;
    }
    this.connections.push(new TornConnection(constraint, now));
    this.permanentlyBrokenPairs.add(pairKey(constraint.first, constraint.second));
    this.markLoose(constraint.first, now + this.profile.adhesionCooldown);
    this.markLoose(constraint.second, now + this.profile.adhesionCooldown);
    this.fragments.push(new TearFragment(constraint.first, constraint.second, breakX, breakY, now));
    this.separateEndpoints(constraint, particles);
    return true;
  }

  hasActiveConnection(first: number, second: number) {
    const key = pairKey(first, second);
    return (this.structuralConstraintByPair.get(key)?.active ?? false)
      || this.adhesions.some((constraint) => (
      constraint.active && pairKey(constraint.first, constraint.second) === key
    ));
  }

  update(particles: ElasticParticle[], now: number, stepSeconds: number) {
    this.resolveBreaks(particles, now);
    this.pruneFragments(now);
    if (now < this.nextCaptureScanAt) {
      return 0;
    }
    this.nextCaptureScanAt = now + 72;
    return this.tryCapture(particles, now, stepSeconds);
  }

  tryCapture(particles: ElasticParticle[], now: number, stepSeconds: number) {
    if (this.activeConstraints.length >= this.profile.maxAdhesionConstraints) {
      return 0;
    }
    for (const [endpointIndex, endpointState] of this.looseEndpoints) {
      if (endpointState.readyAt > now) {
        continue;
      }
      const endpoint = particles[endpointIndex];
      if (!endpoint || endpoint.pinned) {
        continue;
      }
      let candidateIndex = -1;
      let candidateDistance = this.profile.adhesionCaptureRadius;
      let bestScore = Number.POSITIVE_INFINITY;
      for (const [index, candidate] of particles.entries()) {
        if (index === endpointIndex || candidate.pinned) {
          continue;
        }
        const candidateState = this.looseEndpoints.get(index);
        if (candidateState && candidateState.readyAt > now) {
          continue;
        }
        const key = pairKey(endpointIndex, index);
        if (this.permanentlyBrokenPairs.has(key) || this.hasActiveConnection(endpointIndex, index)) {
          continue;
        }
        const distance = Math.hypot(candidate.x - endpoint.x, candidate.y - endpoint.y);
        if (distance <= 2 || distance >= this.profile.adhesionCaptureRadius) {
          continue;
        }
        const relativeSpeed = Math.hypot(
          candidate.velocityX(stepSeconds) - endpoint.velocityX(stepSeconds),
          candidate.velocityY(stepSeconds) - endpoint.velocityY(stepSeconds),
        );
        if (relativeSpeed > this.profile.adhesionMaxRelativeSpeed) {
          continue;
        }
        const score = distance + relativeSpeed * 0.04;
        if (score < bestScore) {
          bestScore = score;
          candidateDistance = distance;
          candidateIndex = index;
        }
      }
      if (candidateIndex < 0) {
        continue;
      }
      this.adhesions.push(new AdhesionConstraint(
        endpointIndex,
        candidateIndex,
        Math.max(3, candidateDistance * 0.82),
        now,
        this.profile,
      ));
      this.looseEndpoints.delete(endpointIndex);
      this.looseEndpoints.delete(candidateIndex);
      return 1;
    }
    return 0;
  }

  resolveBreaks(particles: ElasticParticle[], now: number) {
    for (const constraint of this.adhesions) {
      if (!constraint.active || !constraint.shouldBreak(particles, now)) {
        continue;
      }
      constraint.break(now);
      this.markLoose(constraint.first, now + this.profile.adhesionCooldown);
      this.markLoose(constraint.second, now + this.profile.adhesionCooldown);
      const first = particles[constraint.first];
      const second = particles[constraint.second];
      this.fragments.push(new TearFragment(
        constraint.first,
        constraint.second,
        (first.x + second.x) * 0.5,
        (first.y + second.y) * 0.5,
        now,
      ));
      this.separateEndpoints(constraint, particles);
    }
    for (let index = this.adhesions.length - 1; index >= 0; index -= 1) {
      const constraint = this.adhesions[index];
      if (!constraint.active && now - constraint.brokenAt > this.profile.adhesionCooldown) {
        this.adhesions.splice(index, 1);
      }
    }
  }

  private markLoose(index: number, readyAt: number) {
    const existing = this.looseEndpoints.get(index);
    this.looseEndpoints.set(index, { readyAt: Math.max(existing?.readyAt ?? 0, readyAt) });
  }

  private pruneFragments(now: number) {
    for (let index = this.fragments.length - 1; index >= 0; index -= 1) {
      if (this.fragments[index].expiresAt <= now) {
        this.fragments.splice(index, 1);
      }
    }
  }

  private separateEndpoints(constraint: DistanceConstraint, particles: ElasticParticle[]) {
    const first = particles[constraint.first];
    const second = particles[constraint.second];
    const deltaX = second.x - first.x;
    const deltaY = second.y - first.y;
    const distance = Math.max(0.001, Math.hypot(deltaX, deltaY));
    const impulse = this.profile.compact ? 20 : 28;
    first.addVelocity(-(deltaX / distance) * impulse, -(deltaY / distance) * impulse, XPBD_FIXED_STEP_SECONDS);
    second.addVelocity((deltaX / distance) * impulse, (deltaY / distance) * impulse, XPBD_FIXED_STEP_SECONDS);
  }
}

function restCosine(first: ElasticParticle, middle: ElasticParticle, second: ElasticParticle) {
  const firstX = first.x - middle.x;
  const firstY = first.y - middle.y;
  const secondX = second.x - middle.x;
  const secondY = second.y - middle.y;
  const denominator = Math.max(
    0.001,
    Math.hypot(firstX, firstY) * Math.hypot(secondX, secondY),
  );
  return clampValue((firstX * secondX + firstY * secondY) / denominator, -1, 1);
}

export class ElasticMeshSimulation {
  readonly profile: ElasticMeshProfile;
  readonly topology!: SilkWebTopology;
  readonly particles: ElasticParticle[] = [];
  readonly constraints: DistanceConstraint[] = [];
  readonly bendingConstraints: BendingConstraint[] = [];
  readonly pointer = new PointerInteractor();
  readonly pressureField = new PointerPressureField();
  readonly waveField = new WaveField();
  readonly restShapeConstraint: RestShapeConstraint;
  readonly adhesionManager: AdhesionManager;
  contactPatch: PointerContactPatch | null = null;
  totalSubsteps = 0;
  lastSubstepCount = 0;

  private accumulatorSeconds = 0;
  private readonly constraintByPair = new Map<string, DistanceConstraint>();

  constructor(
    public readonly width: number,
    public readonly height: number,
    public readonly compact: boolean,
    profileName: ElasticMeshProfileName = 'login',
  ) {
    this.profile = resolveElasticMeshProfile(profileName, compact);
    this.restShapeConstraint = new RestShapeConstraint(this.profile.restShapeCompliance);
    this.build();
    this.adhesionManager = new AdhesionManager(this.profile, this.constraints);
  }

  get grabbedIndices() {
    return this.contactPatch?.indices ?? [];
  }

  get grabbedIndex() {
    return this.grabbedIndices[0] ?? -1;
  }

  get adhesionConstraints() {
    return this.adhesionManager.activeConstraints;
  }

  get tornConnections() {
    return this.adhesionManager.connections;
  }

  get tearFragments() {
    return this.adhesionManager.fragments;
  }

  fiberTension(fiber: SilkFiber) {
    let total = 0;
    let count = 0;
    for (let index = 1; index < fiber.particleIndices.length; index += 1) {
      const constraint = this.constraintByPair.get(pairKey(
        fiber.particleIndices[index - 1],
        fiber.particleIndices[index],
      ));
      if (!constraint) {
        continue;
      }
      total += constraint.tension(this.particles);
      count += 1;
    }
    return count > 0 ? total / count : 0;
  }

  isStructuralConnectionActive(first: number, second: number) {
    return this.constraintByPair.get(pairKey(first, second))?.active ?? false;
  }

  movePointer(x: number, y: number, time: number, active = true) {
    this.pointer.move(x, y, time, active);
    if (!active) {
      this.releasePointer(time);
      return;
    }
    this.contactPatch?.setTarget(x, y);
    this.tearAt(x, y, this.pointer.speed, time);
  }

  pressPointer(now = performance.now()) {
    if (!this.pointer.active) {
      return;
    }
    this.pointer.pressed = true;
    this.contactPatch = PointerContactPatch.capture(this.particles, this.pointer, this.profile);
    this.waveField.emit(this.pointer.x, this.pointer.y, this.compact ? 16 : 22, now);
  }

  releasePointer(now = performance.now()) {
    if (this.pointer.pressed) {
      this.waveField.emit(this.pointer.x, this.pointer.y, this.compact ? 12 : 18, now);
    }
    this.pointer.release();
    this.contactPatch = null;
  }

  tearAt(x: number, y: number, speed: number, now: number) {
    if (speed < MIN_TEAR_SPEED || this.tornConnections.length >= this.profile.maxTornConnections) {
      return 0;
    }
    const candidates = this.constraints
      .filter((constraint) => constraint.active
        && constraint.breakable
        && constraint.distanceToPoint(this.particles, x, y) <= this.profile.pointerTearRadius)
      .sort((first, second) => first.distanceToPoint(this.particles, x, y)
        - second.distanceToPoint(this.particles, x, y));
    const perMoveLimit = this.compact ? 1 : 2;
    let brokenThisMove = 0;
    for (const constraint of candidates) {
      if (brokenThisMove >= perMoveLimit
        || this.tornConnections.length >= this.profile.maxTornConnections) {
        break;
      }
      if (this.breakConstraint(constraint, now)) {
        brokenThisMove += 1;
      }
    }
    return brokenThisMove;
  }

  brokenConstraintCount() {
    return this.tornConnections.length;
  }

  activeAdhesionCount() {
    return this.adhesionConstraints.length;
  }

  isOpenEndpoint(index: number) {
    return this.adhesionManager.isOpenEndpoint(index);
  }

  hasActiveConnection(first: number, second: number) {
    return this.adhesionManager.hasActiveConnection(first, second);
  }

  tryCreateAdhesions(now: number) {
    return this.adhesionManager.tryCapture(this.particles, now, XPBD_FIXED_STEP_SECONDS);
  }

  step(time: number, elapsedSeconds: number) {
    return this.advance(time, elapsedSeconds);
  }

  advance(time: number, elapsedSeconds: number) {
    const maximumAccumulated = XPBD_FIXED_STEP_SECONDS * this.profile.maxSubsteps;
    this.accumulatorSeconds = Math.min(
      maximumAccumulated,
      this.accumulatorSeconds + clampValue(elapsedSeconds, 0, 0.1),
    );
    let substeps = 0;
    while (this.accumulatorSeconds + 0.0000001 >= XPBD_FIXED_STEP_SECONDS
      && substeps < this.profile.maxSubsteps) {
      const substepTime = time - this.accumulatorSeconds * 1000;
      this.substep(substepTime, XPBD_FIXED_STEP_SECONDS);
      this.accumulatorSeconds -= XPBD_FIXED_STEP_SECONDS;
      substeps += 1;
    }
    this.lastSubstepCount = substeps;
    this.totalSubsteps += substeps;
    return substeps;
  }

  private substep(time: number, stepSeconds: number) {
    this.adhesionManager.update(this.particles, time, stepSeconds);
    this.pressureField.apply(this.particles, this.pointer, this.profile, stepSeconds);
    this.waveField.apply(this.particles, this.profile, time, stepSeconds);
    for (const particle of this.particles) {
      particle.integrate(stepSeconds, time, this.profile);
    }
    this.contactPatch?.setTarget(this.pointer.x, this.pointer.y);
    for (const constraint of this.constraints) {
      constraint.resetStep();
    }
    for (const constraint of this.bendingConstraints) {
      constraint.resetStep();
    }
    const adhesionConstraints = this.adhesionConstraints;
    for (const constraint of adhesionConstraints) {
      constraint.resetStep();
    }
    this.restShapeConstraint.resetStep();
    this.contactPatch?.resetStep();

    for (let iteration = 0; iteration < this.profile.solverIterations; iteration += 1) {
      for (const constraint of this.constraints) {
        constraint.solve(this.particles, stepSeconds, time);
      }
      for (const constraint of this.bendingConstraints) {
        constraint.solve(this.particles, stepSeconds, time);
      }
      for (const constraint of adhesionConstraints) {
        constraint.solve(this.particles, stepSeconds, time);
      }
      this.restShapeConstraint.solve(this.particles, stepSeconds, time);
      this.contactPatch?.solve(this.particles, stepSeconds, time);
    }
    this.ruptureOverstretched(time);
    this.adhesionManager.resolveBreaks(this.particles, time);
  }

  private ruptureOverstretched(now: number) {
    if (!this.pointer.pressed || !this.contactPatch) {
      return 0;
    }
    const candidates = this.constraints
      .filter((constraint) => constraint.active
        && constraint.breakable
        && constraint.peakStrain >= this.profile.structuralBreakStrain)
      .sort((first, second) => second.peakStrain - first.peakStrain);
    let broken = 0;
    const perStepLimit = this.compact ? 1 : 2;
    for (const constraint of candidates) {
      if (broken >= perStepLimit || this.tornConnections.length >= this.profile.maxTornConnections) {
        break;
      }
      if (this.breakConstraint(constraint, now)) {
        broken += 1;
      }
    }
    return broken;
  }

  private breakConstraint(constraint: DistanceConstraint, now: number) {
    const first = this.particles[constraint.first];
    const second = this.particles[constraint.second];
    const breakX = (first.x + second.x) * 0.5;
    const breakY = (first.y + second.y) * 0.5;
    if (!this.adhesionManager.register(constraint, this.particles, now, breakX, breakY)) {
      return false;
    }
    this.waveField.emit(breakX, breakY, this.compact ? 26 : 36, now);
    return true;
  }

  private build() {
    const topology = new SilkWebTopologyBuilder(this.width, this.height, this.profile).build();
    this.topology = topology;
    for (const node of topology.nodes) {
      this.particles.push(new ElasticParticle(
        node.x,
        node.y,
        node.x,
        node.y,
        node.inverseMass,
        node.row,
        node.column,
      ));
    }
    for (const edge of topology.edges) {
      const first = this.particles[edge.first];
      const second = this.particles[edge.second];
      const constraint = new DistanceConstraint(
        edge.first,
        edge.second,
        Math.hypot(second.x - first.x, second.y - first.y),
        edge.kind,
        edge.kind === 'cross' ? this.profile.crossCompliance : this.profile.structuralCompliance,
        edge.breakable,
      );
      this.constraints.push(constraint);
      this.constraintByPair.set(pairKey(edge.first, edge.second), constraint);
    }
    for (const bend of topology.bends) {
      const dependencies = [
        this.constraintByPair.get(pairKey(bend.first, bend.middle)),
        this.constraintByPair.get(pairKey(bend.middle, bend.second)),
      ].filter((constraint): constraint is DistanceConstraint => constraint !== undefined);
      this.bendingConstraints.push(new BendingConstraint(
        bend.first,
        bend.middle,
        bend.second,
        restCosine(
          this.particles[bend.first],
          this.particles[bend.middle],
          this.particles[bend.second],
        ),
        this.profile.bendingCompliance,
        dependencies,
      ));
    }
  }
}
