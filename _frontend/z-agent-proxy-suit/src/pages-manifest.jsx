import { HomeOutlined, MonitorOutlined, ReadOutlined } from '@ant-design/icons'
import HomePage from './HomePage'
import StatusPage from './StatusPage'
import ComponentDocPage from './pages/ComponentDoc.jsx'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地，suit 内联形态）。 */
export const appMeta = { title: 'agent-proxy 服务台', short: 'z-agent-proxy' }

export const menuItems = [
    { key: '/z-agent-proxy/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-agent-proxy/status', label: '服务状态', icon: <MonitorOutlined /> },
    { key: '/z-agent-proxy/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-agent-proxy/home', Component: HomePage },
    { path: '/z-agent-proxy/status', Component: StatusPage },
    { path: '/z-agent-proxy/docs', Component: ComponentDocPage },
]

export { default as HomePage } from './HomePage'
export { default as LoginPage } from './LoginPage'
// 主壳接线用：文档页统一以 ComponentDocPage 这个名字从主入口导出。
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
