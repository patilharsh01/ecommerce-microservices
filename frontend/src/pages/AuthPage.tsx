import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { ErrorMessage } from '../components/ui';

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const next = safeNext(params.get('next'));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const user = mode === 'login' ? await login(email, password) : await register(name, email, password);
      navigate(next ?? (user.role === 'ADMIN' ? '/admin' : '/'), { replace: true });
    } catch (caught) {
      setError(caught);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-layout">
      <form className="card auth-card" onSubmit={submit} noValidate={false}>
        <h1>{mode === 'login' ? 'Welcome back' : 'Create your account'}</h1>
        <p className="muted">
          {mode === 'login'
            ? 'Sign in to place orders and watch their sagas run.'
            : 'Accounts created here are customers. Admins are provisioned by the platform.'}
        </p>

        <ErrorMessage error={error} title={mode === 'login' ? 'Could not sign in' : 'Could not create the account'} />

        {mode === 'register' && (
          <label className="field">
            <span>Name</span>
            <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={100} autoComplete="name" />
          </label>
        )}
        <label className="field">
          <span>Email</span>
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required autoComplete="email" />
        </label>
        <label className="field">
          <span>Password</span>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required
                 minLength={mode === 'register' ? 8 : undefined} maxLength={72}
                 autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
          {mode === 'register' && <small className="muted">At least 8 characters.</small>}
        </label>

        <button className="button button-block" type="submit" disabled={submitting}>
          {submitting ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>

        <p className="auth-switch">
          {mode === 'login'
            ? <>New here? <Link to={`/register${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Create an account</Link></>
            : <>Already registered? <Link to="/login">Sign in</Link></>}
        </p>
      </form>

      {mode === 'login' && (
        <aside className="card demo-card">
          <h2>Demo accounts</h2>
          <p className="muted">The admin is seeded by docker-compose; register any number of customers.</p>
          <dl className="demo-credentials">
            <dt>Admin</dt>
            <dd><code>admin@shop.local</code> / <code>admin12345</code></dd>
          </dl>
          <button type="button" className="button button-secondary button-small"
                  onClick={() => { setEmail('admin@shop.local'); setPassword('admin12345'); }}>
            Fill in admin credentials
          </button>
        </aside>
      )}
    </div>
  );
}

/** Only same-app paths: never redirect to another origin after sign-in. */
function safeNext(next: string | null): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : null;
}
