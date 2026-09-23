export const THEME_KEY = "poketto:theme";

/** Runs in <head> before paint so a stored choice never flashes the other theme. */
export const themeScript = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;
