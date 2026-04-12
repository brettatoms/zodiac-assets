// Second entry that also imports shared — forces Vite to create a shared chunk
import { shared } from './shared.js';
console.log("page", shared);
