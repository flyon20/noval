import { mount } from '@vue/test-utils';
import AppShell from '../AppShell.vue';

describe('AppShell', () => {
  test('renders app shell slots and top actions', () => {
    const wrapper = mount(AppShell, {
      props: {
        username: 'demo',
        roles: ['USER'],
      },
      slots: {
        default: '<div>page body</div>',
      },
    });

    expect(wrapper.text()).toContain('demo');
    expect(wrapper.text()).toContain('page body');
  });
});
