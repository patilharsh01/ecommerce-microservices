import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { Product } from '../api/types';

export interface CartLine {
  product: Pick<Product, 'id' | 'name' | 'price' | 'sku'>;
  quantity: number;
}

interface CartContextValue {
  lines: CartLine[];
  count: number;
  total: number;
  add: (product: Product, quantity?: number) => void;
  setQuantity: (productId: number, quantity: number) => void;
  remove: (productId: number) => void;
  clear: () => void;
}

const STORAGE_KEY = 'shopsaga.cart';
export const MAX_UNITS_PER_PRODUCT = 100;
const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>(readCart);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(lines));
  }, [lines]);

  const setQuantity = useCallback((productId: number, quantity: number) => {
    setLines((current) => current
      .map((line) => line.product.id === productId
        ? { ...line, quantity: Math.min(MAX_UNITS_PER_PRODUCT, Math.max(0, quantity)) }
        : line)
      .filter((line) => line.quantity > 0));
  }, []);

  const add = useCallback((product: Product, quantity = 1) => {
    setLines((current) => {
      const existing = current.find((line) => line.product.id === product.id);
      if (existing) {
        return current.map((line) => line === existing
          ? { ...line, quantity: Math.min(MAX_UNITS_PER_PRODUCT, line.quantity + quantity) }
          : line);
      }
      const { id, name, price, sku } = product;
      return [...current, { product: { id, name, price, sku }, quantity: Math.min(MAX_UNITS_PER_PRODUCT, quantity) }];
    });
  }, []);

  const remove = useCallback((productId: number) => {
    setLines((current) => current.filter((line) => line.product.id !== productId));
  }, []);

  const clear = useCallback(() => setLines([]), []);

  const value = useMemo<CartContextValue>(() => ({
    lines,
    count: lines.reduce((sum, line) => sum + line.quantity, 0),
    total: lines.reduce((sum, line) => sum + line.product.price * line.quantity, 0),
    add,
    setQuantity,
    remove,
    clear,
  }), [lines, add, setQuantity, remove, clear]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const context = useContext(CartContext);
  if (!context) {
    throw new Error('useCart must be used inside <CartProvider>');
  }
  return context;
}

function readCart(): CartLine[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') as CartLine[];
    return Array.isArray(parsed) ? parsed.filter((line) => line.quantity > 0) : [];
  } catch {
    return [];
  }
}
