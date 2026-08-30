package run.endive.cm.bindgen;

/**
 * Turns WIT names into Java ones. WIT names are kebab-case by construction, so a word boundary is
 * always a hyphen.
 *
 * @see <a href="https://github.com/WebAssembly/component-model/blob/706074c96bc14cfc58469e1bdc452bb4d91921c7/design/mvp/Explainer.md#import-and-export-definitions">Explainer.md, kebab names</a>
 */
final class Names {

    private Names() {}

    /** {@code hello-world} becomes {@code HelloWorld}. */
    static String type(String witName) {
        return join(witName, true);
    }

    /** {@code host-log} becomes {@code hostLog}. */
    static String member(String witName) {
        return join(witName, false);
    }

    private static String join(String witName, boolean leadingCapital) {
        StringBuilder result = new StringBuilder(witName.length());
        boolean capitalize = leadingCapital;
        for (int i = 0; i < witName.length(); i++) {
            char c = witName.charAt(i);
            if (c == '-') {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
