import { HomeOutlined, MonitorOutlined } from '@ant-design/icons'
import HomePage from './HomePage'
import StatusPage from './StatusPage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地，suit 内联形态）。 */
export const appMeta = { title: 'agent-proxy 服务台', short: 'z-agent-proxy' }

export const menuItems = [
    { key: '/z-agent-proxy/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-agent-proxy/status', label: '服务状态', icon: <MonitorOutlined /> },
]

export const routes = [
    { path: '/z-agent-proxy/home', Component: HomePage },
    { path: '/z-agent-proxy/status', Component: StatusPage },
]

export { default as HomePage } from './HomePage'
export { default as LoginPage } from './LoginPage'
