## Description

These are notes I've taken for the [Durham Neighborhood
College](https://www.dconc.gov/county-departments/departments-a-e/board-of-commissioners/special-projects/durham-neighborhood-college)
class of 2023. These notes, also referred to as a Logseq graph, are written using the database
version of [Logseq](https://logseq.com/). See https://tagaholic.me/durham-neighborhood-college to
learn more about this graph.

## Development

There are a couple https://github.com/logseq/nbb-logseq scripts in script/,
mostly for one-off scripts to fix some of the schema.org data. Before using
these scripts, you'll need to have installed nodeJS,
[babashka](https://github.com/babashka/babashka#installation) and
[clojure](https://clojure.org/). With those installed, install deps with `yarn
install`.  Then to run scripts:

```
$ cd script
$ yarn -s nbb-logseq fix_renamed_class_urls.cljs durham-neighborhood-college
Updated 44 block(s) for graph durham-neighborhood-college!
```
