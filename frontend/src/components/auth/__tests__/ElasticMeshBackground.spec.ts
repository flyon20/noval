import fs from 'node:fs';
import path from 'node:path';
import { mount } from '@vue/test-utils';
import ElasticMeshBackground from '../ElasticMeshBackground.vue';
import {
  FIXED_STEP_SECONDS,
  MAX_ADHESION_CONSTRAINTS,
  MAX_MESH_DPR,
  MAX_MESH_NODES,
  MAX_BROKEN_CONSTRAINTS,
  MIN_TEAR_SPEED,
  SilkFiber,
  SilkMaterial,
  SilkWebTopology,
  SilkWebTopologyBuilder,
  resolveCanvasDpr,
  resolveElasticMeshProfile,
  XPBD_FIXED_STEP_SECONDS,
} from '../elastic-mesh';
import {
  AdhesionConstraint,
  BendingConstraint,
  DistanceConstraint,
  ElasticMeshSimulation,
  ElasticParticle,
  PointerContactPatch,
  PointerInteractor,
  PointerPressureField,
  RestShapeConstraint,
  WaveField,
} from '../elastic-mesh-simulation';

function createContext() {
  return {
    arc: vi.fn(),
    beginPath: vi.fn(),
    clearRect: vi.fn(),
    fill: vi.fn(),
    lineTo: vi.fn(),
    moveTo: vi.fn(),
    quadraticCurveTo: vi.fn(),
    setTransform: vi.fn(),
    stroke: vi.fn(),
  };
}

function stubCanvas(matchesReducedMotion: boolean, width = 844, height = 390) {
  const context = createContext();
  const requestAnimationFrame = vi.fn(() => 1);
  const mediaQuery = {
    matches: matchesReducedMotion,
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(() => true),
  } satisfies MediaQueryList;
  vi.stubGlobal('requestAnimationFrame', requestAnimationFrame);
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
  vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery));
  vi.stubGlobal('CanvasRenderingContext2D', class {});
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
    .mockReturnValue(context as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({
    width,
    height,
    top: 0,
    right: width,
    bottom: height,
    left: 0,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
  return { context, requestAnimationFrame };
}

describe('ElasticMeshBackground', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  test('fills the viewport with a dense bounded silk topology', () => {
    const desktopProfile = resolveElasticMeshProfile('login', false);
    const compactProfile = resolveElasticMeshProfile('login', true);
    const desktop = new SilkWebTopologyBuilder(1440, 900, desktopProfile).build();
    const compact = new SilkWebTopologyBuilder(390, 844, compactProfile).build();
    const desktopX = desktop.nodes.map((node) => node.x);
    const desktopY = desktop.nodes.map((node) => node.y);

    expect(resolveCanvasDpr(3)).toBe(MAX_MESH_DPR);
    expect(desktop.nodes.length).toBeGreaterThan(350);
    expect(desktop.nodes.length).toBeLessThanOrEqual(MAX_MESH_NODES);
    expect(compact.nodes.length).toBeLessThan(desktop.nodes.length);
    expect(Math.min(...desktopX)).toBeLessThan(0);
    expect(Math.max(...desktopX)).toBeGreaterThan(1440);
    expect(Math.min(...desktopY)).toBeLessThan(0);
    expect(Math.max(...desktopY)).toBeGreaterThan(900);
    expect(desktop.edges.some((edge) => edge.kind === 'warp')).toBe(true);
    expect(desktop.edges.some((edge) => edge.kind === 'weft')).toBe(true);
    expect(desktop.edges.some((edge) => edge.kind === 'cross')).toBe(true);
    expect(desktop.bends.length).toBeGreaterThan(0);
    expect(desktop.fibers.length).toBe(desktop.columns + desktop.rows);
    expect(MAX_BROKEN_CONSTRAINTS).toBeLessThanOrEqual(10);
    expect(MAX_ADHESION_CONSTRAINTS).toBeLessThanOrEqual(8);
    expect(FIXED_STEP_SECONDS).toBeCloseTo(1 / 120, 6);
    expect(XPBD_FIXED_STEP_SECONDS).toBe(FIXED_STEP_SECONDS);
  });

  test('models the whole silk web and contact systems as stateful objects', () => {
    const simulation = new ElasticMeshSimulation(720, 480, false, 'login');

    expect(simulation.topology).toBeInstanceOf(SilkWebTopology);
    expect(simulation.particles[0]).toBeInstanceOf(ElasticParticle);
    expect(simulation.constraints[0]).toBeInstanceOf(DistanceConstraint);
    expect(simulation.bendingConstraints[0]).toBeInstanceOf(BendingConstraint);
    expect(simulation.pressureField).toBeInstanceOf(PointerPressureField);
    expect(simulation.waveField).toBeInstanceOf(WaveField);
    expect(simulation.restShapeConstraint).toBeInstanceOf(RestShapeConstraint);
    expect(simulation.topology.fibers.every((fiber) => fiber.particleIndices.length > 2)).toBe(true);
    expect(simulation.constraints.every((constraint) => (
      constraint.kind === 'warp' || constraint.kind === 'weft' || constraint.kind === 'cross'
    ))).toBe(true);
  });

  test('owns visible rest and interaction paint in a silk material object', () => {
    const material = new SilkMaterial({ primary: '#d94682', kinetic: '#1f9c88' }, false);
    const compactMaterial = new SilkMaterial({ primary: '#d94682', kinetic: '#1f9c88' }, true);
    const fiber = new SilkFiber('warp', [0, 1, 2], 0.4);
    const restStroke = material.fiberStroke(fiber, 2, 5, 0);
    const tensionStroke = material.fiberStroke(fiber, 2, 5, 0.8);
    const pointerStroke = material.crossStroke(0.1, 0.8);
    const adhesionStroke = material.adhesionStroke(0.7, 0.4);

    expect(material).toBeInstanceOf(SilkMaterial);
    expect(material.baseFiberOpacity).toBeGreaterThanOrEqual(0.15);
    expect(compactMaterial.baseFiberOpacity).toBeGreaterThan(material.baseFiberOpacity);
    expect(restStroke.opacity).toBeGreaterThanOrEqual(0.13);
    expect(restStroke.width).toBeGreaterThanOrEqual(0.5);
    expect(tensionStroke.opacity).toBeGreaterThan(restStroke.opacity);
    expect(tensionStroke.width).toBeGreaterThan(restStroke.width);
    expect(pointerStroke).not.toBeNull();
    expect(pointerStroke!.color).toBe('#1f9c88');
    expect(adhesionStroke.opacity).toBeGreaterThan(restStroke.opacity);
  });

  test('hover pressure affects a local particle region instead of one node', () => {
    const simulation = new ElasticMeshSimulation(720, 480, false);
    const before = simulation.particles.map((particle) => ({ x: particle.x, y: particle.y }));

    simulation.movePointer(360, 240, 16, true);
    simulation.step(32, 1 / 60);

    const movedCount = simulation.particles.filter((particle, index) => (
      Math.hypot(particle.x - before[index].x, particle.y - before[index].y) > 0.0001
    )).length;
    expect(simulation.pressureField.lastAffectedCount).toBeGreaterThan(4);
    expect(movedCount).toBeGreaterThan(4);
  });

  test('captures a weighted patch and ruptures stretched fibers through XPBD', () => {
    const simulation = new ElasticMeshSimulation(720, 480, false);
    simulation.movePointer(360, 240, 16, true);
    simulation.pressPointer(20);

    expect(simulation.contactPatch).toBeInstanceOf(PointerContactPatch);
    expect(simulation.grabbedIndices.length).toBeGreaterThan(1);
    expect(simulation.grabbedIndices.length).toBeLessThanOrEqual(simulation.profile.pointerContactLimit);
    expect(simulation.waveField.impulses.length).toBeGreaterThan(0);

    simulation.movePointer(600, 240, 1016, true);
    expect(simulation.pointer.speed).toBeLessThan(MIN_TEAR_SPEED);
    for (let frame = 0; frame < 18; frame += 1) {
      simulation.step(1032 + frame * 16, 1 / 60);
    }

    expect(simulation.contactPatch!.pullDistance).toBeGreaterThan(200);
    expect(simulation.brokenConstraintCount()).toBeGreaterThan(0);
    expect(simulation.waveField.impulses.length).toBeGreaterThan(0);
  });

  test('applies XPBD distance correction and bounded fixed substeps', () => {
    const pinned = new ElasticParticle(0, 0, 0, 0, 0);
    const stretched = new ElasticParticle(150, 0, 100, 0, 1);
    const constraint = new DistanceConstraint(0, 1, 100, 'warp', 0.0001);
    constraint.solve([pinned, stretched], XPBD_FIXED_STEP_SECONDS, 0);
    expect(stretched.x).toBeLessThan(150);
    expect(constraint.lambda).not.toBe(0);

    const simulation = new ElasticMeshSimulation(720, 480, false);
    simulation.step(16, 1 / 30);
    expect(simulation.lastSubstepCount).toBe(4);
    expect(simulation.lastSubstepCount).toBeLessThanOrEqual(simulation.profile.maxSubsteps);
  });

  test('keeps long-running pressure and waves finite and returns toward the rest shape', () => {
    const simulation = new ElasticMeshSimulation(720, 480, false);
    simulation.movePointer(360, 240, 16, true);
    simulation.pressPointer(20);
    simulation.movePointer(470, 280, 1016, true);
    for (let frame = 0; frame < 24; frame += 1) {
      simulation.step(1032 + frame * 16, 1 / 60);
    }
    const displacedBeforeRelease = Math.max(...simulation.particles.map((particle) => (
      Math.hypot(particle.x - particle.originX, particle.y - particle.originY)
    )));
    simulation.releasePointer(1500);
    simulation.movePointer(470, 280, 1501, false);
    for (let frame = 0; frame < 120; frame += 1) {
      simulation.step(1516 + frame * 16, 1 / 60);
    }
    const displacements = simulation.particles.map((particle) => (
      Math.hypot(particle.x - particle.originX, particle.y - particle.originY)
    ));

    expect(simulation.particles.every((particle) => (
      Number.isFinite(particle.x) && Number.isFinite(particle.y)
    ))).toBe(true);
    expect(Math.max(...displacements)).toBeLessThan(displacedBeforeRelease);
    expect(Math.max(...displacements)).toBeLessThan(180);
  });

  test('keeps torn fibers broken and allows bounded different-neighbor adhesion', () => {
    const simulation = new ElasticMeshSimulation(720, 480, true);
    const target = simulation.constraints.find((constraint) => (
      constraint.kind === 'warp'
      && constraint.breakable
      && !simulation.particles[constraint.first].pinned
      && !simulation.particles[constraint.second].pinned
    ));
    expect(target).toBeDefined();
    const first = simulation.particles[target!.first];
    const second = simulation.particles[target!.second];
    simulation.tearAt(
      (first.x + second.x) * 0.5,
      (first.y + second.y) * 0.5,
      MIN_TEAR_SPEED + 0.8,
      100,
    );
    expect(target!.isBroken()).toBe(true);
    expect(simulation.hasActiveConnection(target!.first, target!.second)).toBe(false);

    const candidateIndex = simulation.particles.findIndex((particle, index) => (
      index !== target!.first
      && index !== target!.second
      && !particle.pinned
      && !simulation.hasActiveConnection(target!.first, index)
    ));
    expect(candidateIndex).toBeGreaterThanOrEqual(0);
    const candidate = simulation.particles[candidateIndex];
    first.x = candidate.x + 4;
    first.y = candidate.y + 3;
    first.previousX = first.x;
    first.previousY = first.y;
    second.previousX = second.x;
    second.previousY = second.y;
    candidate.previousX = candidate.x;
    candidate.previousY = candidate.y;

    expect(simulation.tryCreateAdhesions(399)).toBe(0);
    expect(simulation.tryCreateAdhesions(500)).toBe(1);
    const adhesion = simulation.adhesionConstraints.find((item) => item.active);
    expect(adhesion).toBeInstanceOf(AdhesionConstraint);
    expect(new Set([adhesion!.first, adhesion!.second])).not.toEqual(
      new Set([target!.first, target!.second]),
    );
    expect(simulation.activeAdhesionCount()).toBeLessThanOrEqual(MAX_ADHESION_CONSTRAINTS);
    expect(simulation.brokenConstraintCount()).toBeLessThanOrEqual(MAX_BROKEN_CONSTRAINTS);
  });

  test('keeps the Canvas inert and preserves motion fallbacks', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../ElasticMeshBackground.vue'), 'utf-8');
    const simulationSource = fs.readFileSync(path.resolve(__dirname, '../elastic-mesh-simulation.ts'), 'utf-8');

    expect(source).toContain('aria-hidden="true"');
    expect(source).toContain('pointer-events: none');
    expect(source).toContain('(prefers-reduced-motion: reduce)');
    expect(source).toContain('visibilitychange');
    expect(source).toContain('adhesionConstraints');
    expect(source).toContain('topology.fibers');
    expect(source).toContain('SilkFiber');
    expect(source).toContain('SilkMaterial');
    expect(source).toContain('material.fiberStroke');
    expect(simulationSource).toContain('PointerContactPatch');
    expect(simulationSource).toContain('PointerPressureField');
    expect(simulationSource).toContain('WaveField');
    expect(simulationSource).toContain('RestShapeConstraint');
    expect(simulationSource).toContain('peakStrain');
    expect(source).toContain('quadraticCurveTo');
    expect(source).not.toContain('context.arc');
    expect(source).not.toContain('0.052');
    expect(source).not.toContain('0.065');
    expect(simulationSource).not.toContain('RadialWebTopology');
  });

  test('draws one static frame without scheduling RAF for reduced motion', () => {
    const { context, requestAnimationFrame } = stubCanvas(true);
    const wrapper = mount(ElasticMeshBackground);

    expect(context.stroke).toHaveBeenCalled();
    expect(requestAnimationFrame).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  test('starts the interactive animation loop when motion is allowed', () => {
    const { context, requestAnimationFrame } = stubCanvas(false, 720, 480);
    const wrapper = mount(ElasticMeshBackground);

    expect(context.stroke).toHaveBeenCalled();
    expect(requestAnimationFrame).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  test('tracks pointer velocity as a physical input object', () => {
    const pointer = new PointerInteractor();
    pointer.move(100, 100, 16, true);
    pointer.move(130, 112, 32, true);

    expect(pointer.speed).toBeGreaterThan(0);
    expect(pointer.velocityX).toBeGreaterThan(0);
  });
});
