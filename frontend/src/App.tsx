import { createBrowserRouter, Link, RouterProvider } from 'react-router';
import { RequireAdmin, RequireAuth } from './auth/guards';
import { Layout } from './components/Layout';
import { EmptyState } from './components/ui';
import { AdminLayout } from './pages/admin/AdminLayout';
import { AdminOverviewPage } from './pages/admin/AdminOverviewPage';
import { AdminProductsPage } from './pages/admin/AdminProductsPage';
import { FailureLabPage } from './pages/admin/FailureLabPage';
import { AuthPage } from './pages/AuthPage';
import { CartPage } from './pages/CartPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { OrdersPage } from './pages/OrdersPage';
import { ShopPage } from './pages/ShopPage';

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <ShopPage /> },
      { path: 'cart', element: <CartPage /> },
      { path: 'login', element: <AuthPage mode="login" /> },
      { path: 'register', element: <AuthPage mode="register" /> },
      { path: 'orders', element: <RequireAuth><OrdersPage /></RequireAuth> },
      { path: 'orders/:id', element: <RequireAuth><OrderDetailPage /></RequireAuth> },
      {
        path: 'admin',
        element: <RequireAdmin><AdminLayout /></RequireAdmin>,
        children: [
          { index: true, element: <AdminOverviewPage /> },
          { path: 'products', element: <AdminProductsPage /> },
          { path: 'failure-lab', element: <FailureLabPage /> },
        ],
      },
      { path: '*', element: <EmptyState title="Page not found"><Link to="/">Back to the shop</Link></EmptyState> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
