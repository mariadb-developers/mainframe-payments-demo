/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        display: ['Outfit', 'sans-serif'],
        body: ['IBM Plex Sans', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
        vt: ['VT323', 'monospace'],
      },
      colors: {
        // Mainframe VT100 panel: green phosphor on black.
        'vt': {
          fg: '#33ff66',
          dim: '#1a8033',
          bg: '#0b1a0d',
          accent: '#aaff77',
        },
        // GridGain panel: modern app aesthetic.
        'gg': {
          50: '#f0f7ff',
          100: '#e0effe',
          400: '#36adf8',
          500: '#0c93e9',
          600: '#0074c7',
          700: '#015da1',
        },
        // MariaDB panel: SQL-CLI dark blue.
        'maria': {
          fg: '#cfe9ff',
          bg: '#101a2c',
          accent: '#7dd3fc',
          dim: '#475569',
        },
        'surface': {
          50: '#f8fafc',
          100: '#f1f5f9',
          200: '#e2e8f0',
          300: '#cbd5e1',
          400: '#94a3b8',
          500: '#64748b',
          600: '#475569',
          700: '#334155',
          800: '#1e293b',
          900: '#0f172a',
          950: '#0a1120',
        },
      },
      animation: {
        'fade-in': 'fadeIn 0.3s ease-out',
        'slide-up': 'slideUp 0.3s ease-out',
        'pulse-corr': 'pulseCorr 0.9s ease-out',
      },
      keyframes: {
        fadeIn: { '0%': { opacity: '0' }, '100%': { opacity: '1' } },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseCorr: {
          '0%, 100%': { backgroundColor: 'rgba(125, 211, 252, 0)' },
          '50%': { backgroundColor: 'rgba(125, 211, 252, 0.25)' },
        },
      },
    },
  },
  plugins: [],
}
