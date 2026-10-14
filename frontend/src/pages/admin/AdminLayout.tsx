import { NavLink, Outlet } from 'react-router';

export function AdminLayout() {
  return (
    <>
      <nav className="subnav" aria-label="Operations">
        <NavLink to="/admin" end>Overview</NavLink>
        <NavLink to="/admin/products">Catalog</NavLink>
        <NavLink to="/admin/failure-lab">Failure lab</NavLink>
      </nav>
      <Outlet />
    </>
  );
}
