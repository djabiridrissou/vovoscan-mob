// Loads the build-logic classpath at the root: modules then apply the plugins
// (serialization, SQLDelight, SKIE…) by id, without a version, at the catalog's version.
plugins {
    id("vovoscan.kmp-library") apply false
}
