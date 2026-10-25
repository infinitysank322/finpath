/** Test helper: in-memory Storage so specs never touch the real localStorage. */
export function memoryStorage(seed: Record<string, string> = {}): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> & {
  data: Record<string, string>;
} {
  const data = { ...seed };
  return {
    data,
    getItem: (k: string) => data[k] ?? null,
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
    removeItem: (k: string) => {
      delete data[k];
    },
  };
}
