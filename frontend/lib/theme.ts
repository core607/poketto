export const THEME_KEY = "poketto:theme";

/** The appearance a browser gets before choosing one; the server renders it into <html>. */
export const DEFAULT_THEME = "dark";

/**
 * Runs in <head> before paint so a stored choice never flashes the default. Following the system
 * is a stored choice too, because an absent choice means the default.
 */
export const themeScript = `try{var t=localStorage.getItem("${THEME_KEY}"),r=document.documentElement;if(t==="light"||t==="dark")r.dataset.theme=t;else if(t==="system")delete r.dataset.theme}catch(e){}`;
