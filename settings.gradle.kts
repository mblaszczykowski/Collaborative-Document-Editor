rootProject.name = "collaborative-document-editor"

// crdt-core jest świadomie osobnym modułem BEZ zależności od Springa (ADR-0001).
// Dzięki temu algorytm da się testować i benchmarkować w izolacji, a kompilator
// pilnuje, żeby nic z warstwy aplikacyjnej nie wyciekło do rdzenia.
include("crdt-core")
include("app")

// bench (Gatling) dochodzi w CP8 — nie deklaruję modułu, którego jeszcze nie ma,
// żeby `./gradlew build` na czystym repo zawsze przechodził.
