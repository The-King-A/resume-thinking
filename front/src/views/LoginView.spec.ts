// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './LoginView.vue'

const mocks = vi.hoisted(() => ({
  error: null as string | null,
  routerReplace: vi.fn(),
  browserReplace: vi.fn(),
  redirect: '' as string | string[] | undefined,
}))
const mockLogin = vi.fn().mockResolvedValue(undefined)
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({ login: mockLogin, loading: false, get error() { return mocks.error } }),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.routerReplace }),
  useRoute: () => ({ query: { redirect: mocks.redirect } }),
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
}))

const mountLoginView = () => mount(LoginView, {
  global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } },
})

describe('LoginView', () => {
  beforeEach(() => {
    mockLogin.mockClear()
    mocks.error = null
    mocks.routerReplace.mockReset()
    mocks.routerReplace.mockResolvedValue(undefined)
    mocks.browserReplace.mockReset()
    mocks.redirect = ''
  })
  afterEach(() => vi.unstubAllGlobals())

  it('renders registration and password recovery as separate account actions', () => {
    const wrapper = mountLoginView()

    const links = wrapper.get('nav[aria-label="账号操作"]').findAll('a')
    expect(links).toHaveLength(2)
    expect(links[0]?.attributes('href')).toBe('/register')
    expect(links[0]?.text()).toBe('创建账号')
    expect(links[1]?.attributes('href')).toBe('/forgot-password')
    expect(links[1]?.text()).toBe('忘记密码')
  })

  it('shows the ai-resume-thinking product identity', () => {
    const wrapper = mountLoginView()
    expect(wrapper.get('[data-test="brand-name"]').text()).toBe('ai-resume-thinking')
    expect(wrapper.text()).toContain('简历驱动岗位匹配与模拟面试平台')
  })

  it('submits a valid LoginRequest', async () => {
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('user@example.com')
    await wrapper.get('input[autocomplete="current-password"]').setValue('password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).toHaveBeenCalledWith({ identifier: 'user@example.com', password: 'password' })
  })

  it('rejects an identifier longer than the LoginRequest limit', async () => {
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('x'.repeat(255))
    await wrapper.get('input[autocomplete="current-password"]').setValue('password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('用户名或邮箱长度不能超过 254 个字符')
  })

  it('rejects a password longer than the LoginRequest limit', async () => {
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('user')
    await wrapper.get('input[autocomplete="current-password"]').setValue('x'.repeat(129))
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(mockLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('密码长度不能超过 128 个字符')
  })

  it('does not render an API failure inline after submission', async () => {
    mocks.error = '登录失败，请稍后重试。'
    mockLogin.mockRejectedValueOnce(new Error('network failure'))
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('long-enough-password')
    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('登录失败，请稍后重试。')
  })

  it('uses the in-app unavailable-page fallback if profile navigation fails after authentication', async () => {
    mocks.routerReplace.mockRejectedValueOnce(new Error('profile module unavailable'))
    mocks.routerReplace.mockResolvedValueOnce(undefined)
    vi.stubGlobal('location', { replace: mocks.browserReplace })
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('long-enough-password')

    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()

    expect(mocks.routerReplace).toHaveBeenNthCalledWith(1, '/profiles')
    expect(mocks.routerReplace).toHaveBeenNthCalledWith(2, {
      name: 'route-unavailable',
      query: { retry: '/profiles' },
    })
    expect(mocks.browserReplace).not.toHaveBeenCalled()
  })

  it('returns to the protected interview target after re-authentication', async () => {
    mocks.redirect = '/interviews/new?matchTaskId=task001'
    const wrapper = mountLoginView()
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('long-enough-password')

    await wrapper.get('button[type="submit"]').trigger('click')
    await flushPromises()

    expect(mocks.routerReplace).toHaveBeenCalledWith('/interviews/new?matchTaskId=task001')
  })
})
