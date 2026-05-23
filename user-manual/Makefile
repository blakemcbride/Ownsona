# OwnSona manual --- GNU TexInfo source, HTML + PDF output.
#
# Build:
#     make            # HTML (default)
#     make html       # HTML (split, one chapter per page)
#     make html-one   # HTML (single page)
#     make pdf        # PDF (requires texi2pdf + a TeX install)
#     make info       # GNU info reader format
#     make clean      # remove generated artifacts
#
# The HTML output is committed alongside the source so anyone can
# browse the manual on disk without installing texinfo.  Rebuild
# whenever you edit a .texi file.

# ----------------------------------------------------------------------
# Tooling

MAKEINFO   = makeinfo
TEXI2PDF   = texi2pdf
CSS        = manual.css

# ----------------------------------------------------------------------
# Sources

MAIN       = ownsona.texi

# Every chapter file in the order they appear in ownsona.texi.  The
# top-level @include statements drive the actual structure; this list
# only exists so Make knows when to rebuild.
CHAPTERS = \
    intro.texi              \
    getting-started.texi    \
    install.texi            \
    configuration.texi      \
    mcp-tools.texi          \
    server-internals.texi   \
    client-claude.texi      \
    client-openai.texi      \
    cli.texi                \
    teach.texi              \
    reembed.texi            \
    tutorials.texi          \
    troubleshooting.texi    \
    reference.texi          \
    contributing.texi

SOURCES = $(MAIN) $(CHAPTERS)

# ----------------------------------------------------------------------
# Targets

.PHONY: all html html-one pdf info clean

all: html

# Split HTML: one chapter per file, with auto-navigation.  This is
# what we commit to the repo --- it's the most readable format and
# matches conventional GNU manual layouts.
html: html/index.html

html/index.html: $(SOURCES) $(CSS)
	@rm -rf html
	$(MAKEINFO) --html --css-include=$(CSS) -o html $(MAIN)
	@echo "==> manual built at user-manual/html/index.html"

# Single-page HTML (handy for ctrl-F across the whole manual).  Not
# the default because it's a 200kB+ blob and committing both forms is
# wasteful.  Use 'make html-one' on demand.
html-one: $(SOURCES) $(CSS)
	$(MAKEINFO) --html --no-split --css-include=$(CSS) -o ownsona.html $(MAIN)
	@echo "==> single-page manual built at user-manual/ownsona.html"

pdf: $(SOURCES)
	$(TEXI2PDF) $(MAIN)
	@echo "==> manual built at user-manual/ownsona.pdf"

info: $(SOURCES)
	$(MAKEINFO) --output=ownsona.info $(MAIN)
	@echo "==> manual built at user-manual/ownsona.info"

clean:
	rm -rf html
	rm -f ownsona.html ownsona.info ownsona.pdf
	rm -f *.aux *.cp *.cps *.fn *.fns *.ky *.kys *.log *.pg *.pgs *.toc *.tp *.tps *.vr *.vrs
