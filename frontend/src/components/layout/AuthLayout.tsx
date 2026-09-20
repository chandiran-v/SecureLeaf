interface AuthLayoutProps {
  children: React.ReactNode;
  title: string;
  subtitle: string;
}

/**
 * Shared layout wrapper for login and register pages.
 * Left panel: brand + tagline on a subtle gradient.
 * Right panel: the auth form.
 */
export default function AuthLayout({ children, title, subtitle }: AuthLayoutProps) {
  return (
    <div className="min-h-screen flex">
      {/* ── Brand Panel (left) ──────────────────────────────────────────── */}
      <div className="hidden lg:flex lg:w-1/2 bg-gradient-to-br from-emerald-50 via-white to-emerald-50 items-center justify-center p-12">
        <div className="max-w-md text-center">
          {/* Logo */}
          <div className="mb-8 flex justify-center">
            <div className="w-16 h-16 bg-emerald-600 rounded-2xl flex items-center justify-center shadow-lg shadow-emerald-200">
              <svg
                className="w-9 h-9 text-white"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
                strokeWidth={2}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313-12.454z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M17 4a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2"
                />
              </svg>
            </div>
          </div>

          <h1 className="text-3xl font-bold text-gray-900 tracking-tight">
            SecureLeaf
          </h1>
          <p className="mt-3 text-gray-500 text-lg leading-relaxed">
            The DRM-protected marketplace for digital knowledge.
            Sell your documents. Protect your content.
          </p>

          {/* Feature highlights */}
          <div className="mt-10 space-y-4 text-left">
            {[
              { icon: '🔒', text: 'Content never leaves the secure viewer' },
              { icon: '💧', text: 'Every page is watermarked with buyer identity' },
              { icon: '⚡', text: 'Instant access after purchase — no downloads' },
            ].map((feature, i) => (
              <div key={i} className="flex items-center gap-3 text-gray-600">
                <span className="text-lg">{feature.icon}</span>
                <span className="text-sm">{feature.text}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Form Panel (right) ──────────────────────────────────────────── */}
      <div className="flex-1 flex items-center justify-center p-6 sm:p-12 bg-white">
        <div className="w-full max-w-md">
          {/* Mobile logo */}
          <div className="lg:hidden mb-8 flex justify-center">
            <div className="w-12 h-12 bg-emerald-600 rounded-xl flex items-center justify-center">
              <svg
                className="w-7 h-7 text-white"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
                strokeWidth={2}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313-12.454z"
                />
              </svg>
            </div>
          </div>

          <h2 className="text-2xl font-bold text-gray-900">{title}</h2>
          <p className="mt-2 text-sm text-gray-500">{subtitle}</p>

          <div className="mt-8">{children}</div>
        </div>
      </div>
    </div>
  );
}
