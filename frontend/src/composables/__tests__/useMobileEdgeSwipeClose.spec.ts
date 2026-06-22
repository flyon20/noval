import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { useMobileEdgeSwipeClose } from '../useMobileEdgeSwipeClose';

function setWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  });
  window.dispatchEvent(new Event('resize'));
}

describe('useMobileEdgeSwipeClose', () => {
  test('closes on mobile left-edge horizontal swipe', async () => {
    setWidth(390);
    const close = vi.fn();
    const wrapper = mount(defineComponent({
      setup() {
        const swipe = useMobileEdgeSwipeClose(close);
        return { swipe };
      },
      template: `
        <div
          data-test="surface"
          @touchstart.passive="swipe.onTouchStart"
          @touchend.passive="swipe.onTouchEnd"
        />
      `,
    }));

    await wrapper.get('[data-test="surface"]').trigger('touchstart', {
      touches: [{ clientX: 12, clientY: 100 }],
    });
    await wrapper.get('[data-test="surface"]').trigger('touchend', {
      changedTouches: [{ clientX: 110, clientY: 112 }],
    });

    expect(close).toHaveBeenCalledTimes(1);
  });

  test('ignores vertical or non-edge swipes', async () => {
    setWidth(390);
    const close = vi.fn();
    const wrapper = mount(defineComponent({
      setup() {
        const swipe = useMobileEdgeSwipeClose(close);
        return { swipe };
      },
      template: `
        <div
          data-test="surface"
          @pointerdown.passive="swipe.onPointerStart"
          @pointerup.passive="swipe.onPointerEnd"
        />
      `,
    }));

    await wrapper.get('[data-test="surface"]').trigger('pointerdown', { clientX: 12, clientY: 100 });
    await wrapper.get('[data-test="surface"]').trigger('pointerup', { clientX: 130, clientY: 190 });
    await wrapper.get('[data-test="surface"]').trigger('pointerdown', { clientX: 64, clientY: 100 });
    await wrapper.get('[data-test="surface"]').trigger('pointerup', { clientX: 180, clientY: 104 });

    expect(close).not.toHaveBeenCalled();
  });
});
