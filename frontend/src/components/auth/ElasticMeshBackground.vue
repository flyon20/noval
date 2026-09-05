<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  resolveCanvasDpr,
  type ElasticMeshProfileName,
  type SilkFiber,
  SilkMaterial,
  type SilkStroke,
} from './elastic-mesh';
import { ElasticMeshSimulation } from './elastic-mesh-simulation';

interface NetworkInformationLike {
  saveData?: boolean;
}

const props = withDefaults(defineProps<{
  profile?: ElasticMeshProfileName;
}>(), {
  profile: 'login',
});

const canvasRef = ref<HTMLCanvasElement | null>(null);

let context: CanvasRenderingContext2D | null = null;
let simulation: ElasticMeshSimulation | null = null;
let resizeObserver: ResizeObserver | null = null;
let themeObserver: MutationObserver | null = null;
let motionQuery: MediaQueryList | null = null;
let animationFrame = 0;
let lastFrameTime = 0;
let canvasWidth = 0;
let canvasHeight = 0;
let canvasRect: DOMRect | null = null;
let compactMode = false;
let reducedMotion = false;
let palette = {
  primary: '#d94682',
  kinetic: '#1f9c88',
};
let material = new SilkMaterial(palette, compactMode);

function readPalette() {
  const styles = getComputedStyle(document.documentElement);
  palette = {
    primary: styles.getPropertyValue('--color-primary').trim() || '#d94682',
    kinetic: styles.getPropertyValue('--color-kinetic').trim() || '#1f9c88',
  };
  material = new SilkMaterial(palette, compactMode);
}

function shouldUseCompactMesh(width: number) {
  const connection = (navigator as Navigator & { connection?: NetworkInformationLike }).connection;
  return width <= 768
    || connection?.saveData === true
    || (navigator.hardwareConcurrency > 0 && navigator.hardwareConcurrency <= 4);
}

function drawThread(
  firstX: number,
  firstY: number,
  secondX: number,
  secondY: number,
  stroke: SilkStroke,
) {
  if (!context) {
    return;
  }
  context.strokeStyle = stroke.color;
  context.globalAlpha = Math.min(0.9, Math.max(0, stroke.opacity));
  context.lineWidth = stroke.width;
  context.beginPath();
  context.moveTo(firstX, firstY);
  context.lineTo(secondX, secondY);
  context.stroke();
}

function strokeFiberRun(
  points: Array<{ x: number; y: number }>,
  stroke: SilkStroke,
) {
  if (!context || points.length < 2) {
    return;
  }
  context.strokeStyle = stroke.color;
  context.globalAlpha = stroke.opacity;
  context.lineWidth = stroke.width;
  context.beginPath();
  context.moveTo(points[0].x, points[0].y);
  for (let index = 1; index + 1 < points.length; index += 1) {
    const current = points[index];
    const next = points[index + 1];
    context.quadraticCurveTo(
      current.x,
      current.y,
      (current.x + next.x) * 0.5,
      (current.y + next.y) * 0.5,
    );
  }
  const last = points[points.length - 1];
  context.lineTo(last.x, last.y);
  context.stroke();
}

function drawFiber(fiber: SilkFiber, offset: number, stroke: SilkStroke) {
  if (!simulation || fiber.particleIndices.length < 2) {
    return;
  }
  let run: Array<{ x: number; y: number }> = [];
  const pushParticle = (particleIndex: number) => {
    const particle = simulation!.particles[particleIndex];
    run.push({
      x: particle.x + (fiber.kind === 'warp' ? offset : 0),
      y: particle.y + (fiber.kind === 'weft' ? offset : 0),
    });
  };
  pushParticle(fiber.particleIndices[0]);
  for (let index = 1; index < fiber.particleIndices.length; index += 1) {
    const previous = fiber.particleIndices[index - 1];
    const current = fiber.particleIndices[index];
    if (!simulation.isStructuralConnectionActive(previous, current)) {
      strokeFiberRun(run, stroke);
      run = [];
    }
    pushParticle(current);
  }
  strokeFiberRun(run, stroke);
}

function drawMesh(now = performance.now()) {
  if (!context || !simulation) {
    return;
  }
  context.clearRect(0, 0, canvasWidth, canvasHeight);
  context.lineCap = 'round';
  context.lineJoin = 'round';
  const pointerRadius = simulation.profile.pointerHoverRadius;

  for (const fiber of simulation.topology.fibers) {
    const tension = simulation.fiberTension(fiber);
    const cellSize = fiber.kind === 'warp'
      ? canvasWidth / Math.max(1, simulation.topology.columns - 1)
      : canvasHeight / Math.max(1, simulation.topology.rows - 1);
    const strandCount = simulation.profile.fiberStrands;
    const strandSpan = cellSize * 0.82;
    for (let strand = 0; strand < strandCount; strand += 1) {
      const ratio = strandCount === 1 ? 0.5 : strand / (strandCount - 1);
      const offset = (ratio - 0.5) * strandSpan;
      drawFiber(
        fiber,
        offset,
        material.fiberStroke(fiber, strand, strandCount, tension),
      );
    }
  }

  for (const constraint of simulation.constraints) {
    if (!constraint.active || constraint.kind !== 'cross') {
      continue;
    }
    const tension = constraint.tension(simulation.particles);
    const pointerProximity = simulation.pointer.active && !reducedMotion
      ? Math.max(0, 1 - constraint.midpointDistance(
        simulation.particles,
        simulation.pointer.x,
        simulation.pointer.y,
      ) / pointerRadius)
      : 0;
    const stroke = material.crossStroke(tension, pointerProximity);
    if (!stroke) {
      continue;
    }
    const first = simulation.particles[constraint.first];
    const second = simulation.particles[constraint.second];
    drawThread(
      first.x,
      first.y,
      second.x,
      second.y,
      stroke,
    );
  }

  for (const adhesion of simulation.adhesionConstraints) {
    const first = simulation.particles[adhesion.first];
    const second = simulation.particles[adhesion.second];
    const maturity = adhesion.maturity(now);
    drawThread(
      first.x,
      first.y,
      second.x,
      second.y,
      material.adhesionStroke(maturity, adhesion.tension(simulation.particles)),
    );
  }

  for (const fragment of simulation.tearFragments) {
    const first = simulation.particles[fragment.first];
    const second = simulation.particles[fragment.second];
    const opacity = fragment.opacity(now);
    drawThread(
      fragment.breakX,
      fragment.breakY,
      first.x,
      first.y,
      material.tearStroke(opacity, true),
    );
    drawThread(
      fragment.breakX,
      fragment.breakY,
      second.x,
      second.y,
      material.tearStroke(opacity, false),
    );
  }

  context.globalAlpha = 1;
}

function stopAnimation() {
  if (animationFrame) {
    cancelAnimationFrame(animationFrame);
    animationFrame = 0;
  }
}

function animate(time: number) {
  animationFrame = 0;
  if (document.hidden || reducedMotion) {
    drawMesh(time);
    return;
  }
  const frameInterval = compactMode ? 1000 / 30 : 1000 / 60;
  if (lastFrameTime === 0 || time - lastFrameTime >= frameInterval) {
    const elapsedSeconds = lastFrameTime === 0
      ? frameInterval / 1000
      : Math.min(0.05, (time - lastFrameTime) / 1000);
    simulation?.advance(time, elapsedSeconds);
    drawMesh(time);
    lastFrameTime = time;
  }
  animationFrame = requestAnimationFrame(animate);
}

function startAnimation() {
  if (!animationFrame && !document.hidden && !reducedMotion) {
    animationFrame = requestAnimationFrame(animate);
  }
}

function resizeCanvas() {
  const canvas = canvasRef.value;
  if (!canvas || !context) {
    return;
  }
  canvasRect = canvas.getBoundingClientRect();
  canvasWidth = Math.max(1, canvasRect.width);
  canvasHeight = Math.max(1, canvasRect.height);
  compactMode = shouldUseCompactMesh(canvasWidth);
  material = new SilkMaterial(palette, compactMode);
  const dpr = resolveCanvasDpr(window.devicePixelRatio);
  canvas.width = Math.round(canvasWidth * dpr);
  canvas.height = Math.round(canvasHeight * dpr);
  context.setTransform(dpr, 0, 0, dpr, 0, 0);
  simulation = new ElasticMeshSimulation(canvasWidth, canvasHeight, compactMode, props.profile);
  lastFrameTime = 0;
  drawMesh();
}

function moveSimulationPointer(event: PointerEvent) {
  if (!canvasRect || !simulation) {
    return false;
  }
  const x = event.clientX - canvasRect.left;
  const y = event.clientY - canvasRect.top;
  const active = x >= 0 && x <= canvasRect.width && y >= 0 && y <= canvasRect.height;
  simulation.movePointer(x, y, performance.now(), reducedMotion ? false : active);
  return active;
}

function handlePointerMove(event: PointerEvent) {
  moveSimulationPointer(event);
}

function handlePointerDown(event: PointerEvent) {
  if (moveSimulationPointer(event) && !reducedMotion) {
    simulation?.pressPointer();
  }
}

function releasePointer() {
  simulation?.releasePointer();
}

function handleVisibilityChange() {
  if (document.hidden) {
    stopAnimation();
  } else {
    lastFrameTime = 0;
    startAnimation();
  }
}

function handleMotionPreference(event: MediaQueryListEvent | MediaQueryList) {
  reducedMotion = event.matches;
  simulation?.releasePointer();
  stopAnimation();
  drawMesh();
  startAnimation();
}

onMounted(() => {
  const canvas = canvasRef.value;
  if (!canvas || typeof CanvasRenderingContext2D === 'undefined') {
    return;
  }
  context = canvas.getContext('2d');
  if (!context) {
    return;
  }
  readPalette();
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  reducedMotion = motionQuery.matches;
  motionQuery.addEventListener('change', handleMotionPreference);
  window.addEventListener('pointermove', handlePointerMove, { passive: true });
  window.addEventListener('pointerdown', handlePointerDown, { passive: true });
  window.addEventListener('pointerup', releasePointer, { passive: true });
  window.addEventListener('pointercancel', releasePointer, { passive: true });
  window.addEventListener('blur', releasePointer);
  window.addEventListener('resize', resizeCanvas, { passive: true });
  document.addEventListener('visibilitychange', handleVisibilityChange);
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(resizeCanvas);
    resizeObserver.observe(canvas);
  }
  themeObserver = new MutationObserver(() => {
    readPalette();
    drawMesh();
  });
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  resizeCanvas();
  startAnimation();
});

onBeforeUnmount(() => {
  stopAnimation();
  resizeObserver?.disconnect();
  themeObserver?.disconnect();
  motionQuery?.removeEventListener('change', handleMotionPreference);
  window.removeEventListener('pointermove', handlePointerMove);
  window.removeEventListener('pointerdown', handlePointerDown);
  window.removeEventListener('pointerup', releasePointer);
  window.removeEventListener('pointercancel', releasePointer);
  window.removeEventListener('blur', releasePointer);
  window.removeEventListener('resize', resizeCanvas);
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  simulation = null;
  context = null;
});
</script>

<template>
  <canvas
    ref="canvasRef"
    class="elastic-mesh-background"
    aria-hidden="true"
    tabindex="-1"
  ></canvas>
</template>

<style scoped lang="scss">
.elastic-mesh-background {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 1;
  contain: strict;
}

@media (prefers-reduced-motion: reduce) {
  .elastic-mesh-background {
    opacity: 0.92;
  }
}
</style>
