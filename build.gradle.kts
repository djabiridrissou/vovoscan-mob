// Charge le classpath de build-logic à la racine : les modules appliquent ensuite les plugins
// (sérialisation, SQLDelight, SKIE…) par leur id, sans version, à celle du catalogue.
plugins {
    id("vovoscan.kmp-library") apply false
}
