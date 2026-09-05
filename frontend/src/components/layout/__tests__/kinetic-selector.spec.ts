import { XpbdSelectorObject } from '@/lib/xpbd-selector';

describe('XpbdSelectorObject', () => {
  test('converges to a new navigation slot through an XPBD target constraint', () => {
    const selector = new XpbdSelectorObject(0);
    selector.setTarget(4);

    for (let frame = 0; frame < 90 && !selector.isSettled(); frame += 1) {
      selector.step(1 / 60);
    }

    expect(selector.position).toBeCloseTo(4, 3);
    expect(selector.isSettled()).toBe(true);
  });

  test('can jump directly to the target for reduced-motion users', () => {
    const selector = new XpbdSelectorObject(1);
    selector.setTarget(3);
    selector.jumpTo(3);

    expect(selector.position).toBe(3);
    expect(selector.target).toBe(3);
    expect(selector.isSettled()).toBe(true);
  });
});
