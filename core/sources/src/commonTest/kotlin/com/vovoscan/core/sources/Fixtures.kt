package com.vovoscan.core.sources

/** Manifest copies: vovo-plugin/sources/gutenberg/source.json and the fake HTML site from the backend tests. */
internal object Fixtures {
    val gutenberg =
        """
{
  "${'$'}schema": "../../schema/source.schema.json",
  "contractVersion": 1,
  "key": "gutenberg",
  "name": "Project Gutenberg",
  "version": "1.0.0",
  "description": "Classic books from the Project Gutenberg catalog (public domain in the United States).",
  "kinds": ["book"],
  "langs": ["en", "fr", "de", "es", "it", "pt", "nl"],
  "rights": {
    "basis": "public-domain",
    "notes": "Public domain in the United States; check local copyright terms elsewhere. Books still under copyright are excluded (copyright=false). Free distribution keeps the Project Gutenberg licence notice inside each file; charging for access requires a 20% royalty, unless every Project Gutenberg reference is removed. No automated access to gutenberg.org: catalog from a self-hosted Gutendex, files from a mirror."
  },
  "settings": {
    "catalogBaseUrl": {
      "label": "Gutendex instance",
      "type": "url",
      "required": true,
      "help": "Self-hosted Gutendex built from the Project Gutenberg offline catalog (github.com/garethbjohnson/gutendex)."
    },
    "filesBaseUrl": {
      "label": "Project Gutenberg mirror",
      "type": "url",
      "required": true,
      "help": "A private or official mirror serving /cache/epub/<id>/. Readers' devices download each book from it once, then read it offline."
    },
    "languages": {
      "label": "Languages",
      "type": "string",
      "default": "en,fr",
      "help": "Comma-separated ISO 639-1 codes."
    }
  },
  "rateLimit": {"requestsPerMinute": 30},
  "catalog": {
    "request": {
      "url": "{{settings.catalogBaseUrl}}/books",
      "type": "json",
      "query": {
        "page": "{{page}}",
        "search": "{{search}}",
        "languages": "{{settings.languages}}",
        "copyright": "false",
        "mime_type": "application/epub"
      }
    },
    "list": "${'$'}.results[*]",
    "item": {
      "id": {"path": "${'$'}.id", "then": ["string"]},
      "name": {"path": "${'$'}.title", "then": ["collapseSpaces"]},
      "cover": {"path": "${'$'}.id", "then": [{"format": "{{settings.filesBaseUrl}}/cache/epub/{value}/pg{value}.cover.medium.jpg"}]},
      "kind": {"const": "book"},
      "format": {"const": "text"},
      "status": {"const": "completed"}
    },
    "hasNext": {"path": "${'$'}.next", "then": ["notNull"]},
    "total": "${'$'}.count"
  },
  "title": {
    "request": {"url": "{{settings.catalogBaseUrl}}/books/{{id}}", "type": "json"},
    "fields": {
      "name": {"path": "${'$'}.title", "then": ["collapseSpaces"]},
      "description": {"path": "${'$'}.summaries[0]", "default": ""},
      "cover": {"path": "${'$'}.id", "then": [{"format": "{{settings.filesBaseUrl}}/cache/epub/{value}/pg{value}.cover.medium.jpg"}]},
      "authors": {"path": "${'$'}.authors[*]", "all": true, "then": [{"pluck": "${'$'}.name"}]},
      "genres": {"path": "${'$'}.bookshelves", "default": []},
      "status": {"const": "completed"},
      "format": {"const": "text"}
    },
    "units": {"single": {"id": "{{id}}", "name": "Full text"}}
  },
  "content": {
    "book": {
      "document": {
        "url": {"template": "{{settings.filesBaseUrl}}/cache/epub/{{id}}/pg{{id}}-images.epub"},
        "mediaType": "application/epub+zip"
      }
    }
  }
}

        """.trimIndent()

    val inkwell =
        """
{
  "contractVersion": 1,
  "key": "inkwell",
  "name": "Inkwell (test comic site)",
  "version": "1.0.0",
  "kinds": ["comic"],
  "langs": ["en"],
  "rights": {"basis": "licensed", "notes": "Test fixture: an HTML comic site under licence."},
  "hosts": ["comics.inkwell.example"],
  "catalog": {
    "request": {"url": "https://comics.inkwell.example/browse", "type": "html", "query": {"p": "{{page}}", "q": "{{search}}"}},
    "list": {"css": "div.card"},
    "item": {
      "id": {"css": "a", "attr": "href", "then": [{"regex": "/series/([a-z0-9-]+)"}]},
      "name": {"css": "h3", "then": ["trim"]},
      "cover": {"css": "img", "attr": "src", "then": ["absUrl"]},
      "format": {"css": ".tag", "then": ["trim", "lower", {"map": {"vertical": "webtoon"}, "default": "manga"}]}
    },
    "hasNext": {"css": "a.next", "attr": "href", "then": ["notNull"]}
  },
  "title": {
    "request": {"url": "https://comics.inkwell.example/series/{{id}}", "type": "html"},
    "fields": {
      "name": {"css": "h1", "then": ["trim"]},
      "description": {"css": ".synopsis", "then": ["collapseSpaces"]},
      "authors": {"css": ".author", "all": true, "then": ["trim"]}
    },
    "units": {
      "list": {"css": "ul.chapters li a"},
      "item": {
        "id": {"css": ":self", "attr": "href", "then": [{"regex": "/read/([0-9]+)"}]},
        "number": {"css": ":self", "then": [{"regex": "Chapter ([0-9.]+)"}, "number"]},
        "name": {"css": ":self", "then": [{"regex": "Chapter [0-9.]+: (.+)"}]}
      }
    }
  },
  "content": {
    "comic": {
      "request": {"url": "https://comics.inkwell.example/read/{{unitId}}", "type": "html"},
      "list": {"css": "div.reader img"},
      "item": {
        "url": {"css": ":self", "attr": "data-src", "then": ["absUrl"]},
        "width": {"css": ":self", "attr": "width", "then": ["integer"]},
        "height": {"css": ":self", "attr": "height", "then": ["integer"]}
      }
    }
  }
}

        """.trimIndent()
}
