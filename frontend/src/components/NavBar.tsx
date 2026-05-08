import { useNavigate, useLocation, Link } from 'react-router-dom'
import { LogOut, Zap } from 'lucide-react'
import { logout } from '../lib/api'
import { cn } from '../lib/utils'

export default function NavBar() {
  const navigate = useNavigate()
  const location = useLocation()

  const handleLogout = async () => {
    await logout()
    navigate('/')
  }

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur-sm sticky top-0 z-10">
      <div className="max-w-screen-xl mx-auto px-6 h-14 flex items-center justify-between">
        {/* Logo */}
        <Link to="/upload" className="flex items-center gap-2 group">
          <div className="w-7 h-7 rounded-lg bg-indigo-600 flex items-center justify-center">
            <Zap className="w-4 h-4 text-white" />
          </div>
          <span className="font-semibold text-slate-100 text-sm tracking-tight">ContextFlow</span>
        </Link>

        {/* Nav links */}
        <nav className="flex items-center gap-1">
          <NavLink to="/upload" active={location.pathname === '/upload'}>
            Documents
          </NavLink>
          <NavLink to="/chat" active={location.pathname === '/chat'}>
            Chat
          </NavLink>
        </nav>

        {/* Logout */}
        <button
          onClick={handleLogout}
          className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-slate-200 transition-colors px-2 py-1.5 rounded-md hover:bg-slate-800"
        >
          <LogOut className="w-3.5 h-3.5" />
          Logout
        </button>
      </div>
    </header>
  )
}

function NavLink({
  to,
  active,
  children,
}: {
  to: string
  active: boolean
  children: React.ReactNode
}) {
  return (
    <Link
      to={to}
      className={cn(
        'px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
        active
          ? 'bg-slate-800 text-slate-100'
          : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60',
      )}
    >
      {children}
    </Link>
  )
}
