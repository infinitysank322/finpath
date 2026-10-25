/** Test helper: in-memory Storage so specs never touch the real localStorage. */
export function memoryStorage(seed: Record<string, string> = {}): Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key'> & {
  data: Record<string, string>;
  readonly length: number;
} {
  const data = { ...seed };
  return {
    data,
    get length() {
      return Object.keys(data).length;
    },
    key: (i: number) => Object.keys(data)[i] ?? null,
    getItem: (k: string) => data[k] ?? null,
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
    removeItem: (k: string) => {
      delete data[k];
    },
  };
}
