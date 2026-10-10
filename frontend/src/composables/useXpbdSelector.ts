import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { XpbdSelectorObject } from '@/lib/xpbd-selector';

export function useXpbdSelector(target: Readonly<Ref<number>>) {
  const initialPosition = Math.max(0, target.value);
  const selector = new XpbdSelectorObject(initialPosition);
  const position = ref(initialPosition);
  let animationFrame = 0;
  let mounted = false;
  let reducedMotion = false;
  let motionQuery: MediaQueryList | null = null;

  function stop() {
    if (animationFrame) {
      cancelAnimationFrame(animationFrame);
      animationFrame = 0;
    }
  }

  function animate() {
    animationFrame = 0;
    position.value = selector.step(1 / 60);
    if (!selector.isSettled()) {
      animationFrame = requestAnimationFrame(animate);
    }
  }

  function moveTo(nextTarget: number) {
    if (nextTarget < 0) {
      return;
    }
    if (!mounted || reducedMotion) {
      stop();
      selector.jumpTo(nextTarget);
      position.value = nextTarget;
      return;
    }
    selector.setTarget(nextTarget);
    if (!animationFrame) {
      animationFrame = requestAnimationFrame(animate);
    }
  }

  function handleMotionPreference(event: MediaQueryListEvent | MediaQueryList) {
    reducedMotion = event.matches;
    moveTo(target.value);
  }

  watch(target, moveTo);

  onMounted(() => {
    mounted = true;
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    reducedMotion = motionQuery.matches;
    motionQuery.addEventListener('change', handleMotionPreference);
    moveTo(target.value);
  });

  onBeforeUnmount(() => {
    mounted = false;
    stop();
    motionQuery?.removeEventListener('change', handleMotionPreference);
  });

  return { position };
}
