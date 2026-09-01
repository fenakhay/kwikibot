package com.fenakhay.kwikibot.wikitext

internal object HtmlEntities {

    /**
     * Named entities, as HTML5 defines them.
     *
     * Held as a set because the tokenizer only has to answer "is this a real entity" — decoding
     * happens later, when a caller asks for the text a node represents.
     */
    val NAMES: Set<String> = setOf(
        "Aacute", "aacute", "Acirc", "acirc", "acute", "AElig", "aelig", "Agrave", "agrave",
        "alefsym", "Alpha", "alpha", "amp", "and", "ang", "apos", "Aring", "aring", "asymp",
        "Atilde", "atilde", "Auml", "auml", "bdquo", "Beta", "beta", "brvbar", "bull", "cap",
        "Ccedil", "ccedil", "cedil", "cent", "Chi", "chi", "circ", "clubs", "cong", "copy",
        "crarr", "cup", "curren", "Dagger", "dagger", "dArr", "darr", "deg", "Delta", "delta",
        "diams", "divide", "Eacute", "eacute", "Ecirc", "ecirc", "Egrave", "egrave", "empty",
        "emsp", "ensp", "Epsilon", "epsilon", "equiv", "Eta", "eta", "ETH", "eth", "Euml",
        "euml", "euro", "exist", "fnof", "forall", "frac12", "frac14", "frac34", "frasl",
        "Gamma", "gamma", "ge", "gt", "hArr", "harr", "hearts", "hellip", "Iacute", "iacute",
        "Icirc", "icirc", "iexcl", "Igrave", "igrave", "image", "infin", "int", "Iota", "iota",
        "iquest", "isin", "Iuml", "iuml", "Kappa", "kappa", "Lambda", "lambda", "lang", "laquo",
        "lArr", "larr", "lceil", "ldquo", "le", "lfloor", "lowast", "loz", "lrm", "lsaquo",
        "lsquo", "lt", "macr", "mdash", "micro", "middot", "minus", "Mu", "mu", "nabla", "nbsp",
        "ndash", "ne", "ni", "not", "notin", "nsub", "Ntilde", "ntilde", "Nu", "nu", "Oacute",
        "oacute", "Ocirc", "ocirc", "OElig", "oelig", "Ograve", "ograve", "oline", "Omega",
        "omega", "Omicron", "omicron", "oplus", "or", "ordf", "ordm", "Oslash", "oslash",
        "Otilde", "otilde", "otimes", "Ouml", "ouml", "para", "part", "permil", "perp", "Phi",
        "phi", "Pi", "pi", "piv", "plusmn", "pound", "Prime", "prime", "prod", "prop", "Psi",
        "psi", "quot", "radic", "rang", "raquo", "rArr", "rarr", "rceil", "rdquo", "real",
        "reg", "rfloor", "Rho", "rho", "rlm", "rsaquo", "rsquo", "sbquo", "Scaron", "scaron",
        "sdot", "sect", "shy", "Sigma", "sigma", "sigmaf", "sim", "spades", "sub", "sube",
        "sum", "sup", "sup1", "sup2", "sup3", "supe", "szlig", "Tau", "tau", "there4", "Theta",
        "theta", "thetasym", "thinsp", "THORN", "thorn", "tilde", "times", "trade", "Uacute",
        "uacute", "uArr", "uarr", "Ucirc", "ucirc", "Ugrave", "ugrave", "uml", "upsih",
        "Upsilon", "upsilon", "Uuml", "uuml", "weierp", "Xi", "xi", "Yacute", "yacute", "yen",
        "Yuml", "yuml", "Zeta", "zeta", "zwj", "zwnj",
    )
}
