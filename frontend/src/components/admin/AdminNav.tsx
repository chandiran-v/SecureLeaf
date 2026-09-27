import { NavLink } from 'react-router-dom';

const TABS: { to: string; label: string; end?: boolean }[] = [
  { to: '/admin', label: 'Dashboard', end: true },
  { to: '/admin/users', label: 'Users' },
  { to: '/admin/products', label: 'Products' },
  { to: '/admin/audit-log', label: 'Audit Log' },
];

/** Tab nav shared by the four /admin pages (Dashboard/Users/Products/Audit log). */
export default function AdminNav() {
  return (
    <nav className="flex items-center gap-1 border-b border-gray-200 mb-6" aria-label="Admin sections">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) =>
            `px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
              isActive
                ? 'border-emerald-600 text-emerald-700'
                : 'border-transparent text-gray-500 hover:text-gray-800'
            }`
          }
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
