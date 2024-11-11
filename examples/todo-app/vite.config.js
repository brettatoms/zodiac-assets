import { defineConfig } from "vite"

export default defineConfig({
  build: {
    watch: true,
    outDir: "resources/todo",
    manifest: true,
    rollupOptions: {
      input: [
        "src/todo.ts",
        "src/todo.css",
      ],
    },
  },
})
