// Silence warning about references to NodeJS symbols that don't exist in the browser.
// See https://kotlinlang.org/docs/js-project-setup.html#webpack-configuration-file
config.resolve = {
    fallback: {
        fs: false,
        os: false,
        path: false
    }
};
