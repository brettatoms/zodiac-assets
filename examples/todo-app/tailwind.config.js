/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{html,js,ts,clj,css}",
    "./resources/**/*.{html,js,ts,clj,css}",
  ],
  theme: {
    extend: {},
  },
  plugins: [require('@tailwindcss/forms')],
}
