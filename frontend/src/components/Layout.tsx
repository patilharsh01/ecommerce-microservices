import { NavLink, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import { toolLinks } from '../lib/links';

export function Layout() {
  const { user, isAdmin, logout } = useAuth();
  const { count } = useCart();
  const navigate = useNavigate();

  return (
    <div className="app">
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="topbar">
        <div className="topbar-inner">
          <NavLink to="/" className="brand" aria-label="ShopSaga home">
            <span className="brand-mark" aria-hidden="true">S</span>
            <span>ShopSaga</span>
          </NavLink>
          <nav className="nav" aria-label="Main">
            <NavLink to="/" end>Shop</NavLink>
            {user && <NavLink to="/orders">My orders</NavLink>}
            {isAdmin && <NavLink to="/admin">Operations</NavLink>}
          </nav>
          <div className="topbar-actions">
            <NavLink to="/cart" className="cart-link" aria-label={`Cart, ${count} item(s)`}>
              Cart{count > 0 && <span className="cart-count">{count}</span>}
            </NavLink>
            {user ? (
              <div className="user-chip">
                <span className="avatar" aria-hidden="true">{user.name.slice(0, 1).toUpperCase()}</span>
                <span className="user-name">{user.name}</span>
                <button type="button" className="button button-ghost button-small"
                        onClick={() => { logout(); navigate('/'); }}>
                  Sign out
                </button>
              </div>
            ) : (
              <NavLink to="/login" className="button button-small">Sign in</NavLink>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="container">
        <Outlet />
      </main>
      <footer className="footer">
        <span>Orchestrated saga · transactional outbox · idempotent consumers</span>
        <span className="footer-links">
          <a href={toolLinks.swagger()} target="_blank" rel="noopener noreferrer">API docs</a>
          <a href={toolLinks.grafana()} target="_blank" rel="noopener noreferrer">Grafana</a>
          <a href={toolLinks.jaeger()} target="_blank" rel="noopener noreferrer">Jaeger</a>
        </span>
      </footer>
    </div>
  );
}
