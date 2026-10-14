import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { configureAuth } from '../api/client';
import { api } from '../api/endpoints';
import type { AuthResponse, User } from '../api/types';

interface Session {
  token: string;
  expiresAt: string;
  user: User;
}

interface AuthContextValue {
  user: User | null;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<User>;
  register: (name: string, email: string, password: string) => Promise<User>;
  logout: () => void;
}

const STORAGE_KEY = 'shopsaga.session';
const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Keeps the access token in localStorage so a reload doesn't sign you out. That makes it
 * readable by any script on the page — acceptable for a demo; a production storefront would
 * use an httpOnly cookie issued by a backend-for-frontend instead.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(readSession);
  const sessionRef = useRef(session);
  sessionRef.current = session;

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
    queryClient.clear();
  }, [queryClient]);

  const start = useCallback((response: AuthResponse) => {
    const next: Session = { token: response.accessToken, expiresAt: response.expiresAt, user: response.user };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    queryClient.clear();
    setSession(next);
    return response.user;
  }, [queryClient]);

  useEffect(() => {
    configureAuth(() => sessionRef.current?.token ?? null, logout);
  }, [logout]);

  // Sign out the moment the token expires instead of waiting for a 401.
  useEffect(() => {
    if (!session) return;
    const remaining = new Date(session.expiresAt).getTime() - Date.now();
    const timer = window.setTimeout(logout, Math.max(0, remaining));
    return () => window.clearTimeout(timer);
  }, [session, logout]);

  const value = useMemo<AuthContextValue>(() => ({
    user: session?.user ?? null,
    isAdmin: session?.user.role === 'ADMIN',
    login: async (email, password) => start(await api.login(email, password)),
    register: async (name, email, password) => start(await api.register(name, email, password)),
    logout,
  }), [session, start, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}

function readSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt).getTime() > Date.now() ? session : null;
  } catch {
    return null;
  }
}
