# go-rat-annotation-pipeline

Imports rat GO annotations from EBI/GOA into RGD.

## Source files

Downloaded from EBI's GOA RAT FTP directory:

```
ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/RAT/goa_rat.gaf.gz
ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/RAT/goa_rat_complex.gaf.gz
ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/RAT/goa_rat_isoform.gaf.gz
ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/RAT/goa_rat_rna.gaf.gz
```

GO relation file (for annotation extension validation):

```
http://current.geneontology.org/ontology/extensions/gorel.obo
```

## What the pipeline does

1. **Download and merge** the four GAF 2.2 source files into one local file
   (`data/gene_association.goa_rat.gz`).
2. **Filter out RGD-authored annotations** — anything whose reference RGD ID is in
   the configured `refRgdIdsForGoPipelines` set is dropped because those records
   already exist in RGD. Only third-party (non-RGD) annotations are considered
   for loading. The filtered set is written to `data/goa_rat_nonrgd` (and a
   narrower `data/goa_rat` containing only UniProtKB / RefSeq / ENSEMBL rows).
3. **Apply GOC-rule rejections**:
   - Uninformative high-level GO terms are skipped (logged to
     `highLevelGoTerm.log`).
   - IPI annotations to catalytic-activity molecular function terms are skipped
     per `GO_AR:0000007` (logged to `catalyticActivityIPIGoTerm.log`).
4. **Apply substitutions** (configurable in `AppConfigure.xml`):
   - Source: `UniProtKB` → `UniProt`.
   - Qualifier: `colocalizes_with` → `located_in`,
     `NOT|colocalizes_with` → `NOT|located_in`.
5. **Resolve PubMed references** — any PubMed IDs in the incoming references that
   are not yet in RGD are imported via the `importReferences` web tool
   (configured per host in `pubMedManager`).
6. **Insert / update / delete** annotations in `FULL_ANNOT`. Stale annotations
   (those not refreshed by the current run) are deleted, gated by a
   `staleAnnotDeleteThreshold = 5%` safeguard — if more than 5% of existing
   annotations would be deleted, the delete is skipped with a warning.
7. **Olr gene special pass** — see in-code comment in `GoAnnotManager`.
8. **Write a residual file** (`data/goa_rgd.txt`) containing non-RGD annotations
   that could not be loaded (duplicates or non-matching genes), formed by
   concatenating `duplAnnot.log` and `unMatchedDbObjID.log`.

## Configuration

`src/main/dist/properties/AppConfigure.xml` holds:

- `goaRatFiles` — list of source GAF URLs.
- `refRgdIdsForGoPipelines` — RGD reference IDs whose annotations are owned by
  RGD's own pipelines (and therefore filtered out of the GOA import).
- `staleAnnotDeleteThreshold` — percent ceiling on stale-delete count
  (default `5%`).
- `sourceSubst` and `qualifierSubst` — string normalizations applied to incoming
  rows.
- `pubMedManager.importPubmedToolHost` — per-host base URL for the PubMed
  import tool.

## Logs

Written to `logs/` next to the running jar:

| File | Purpose |
|---|---|
| `GoaSummary.log` | high-level summary; mailed by `run.sh` |
| `loaded.log` | inserted annotations |
| `rejected.log` | annotations rejected by validation rules |
| `duplAnnot.log` | incoming annotation already exists in RGD |
| `unMatchedDbObjID.log` | gene xref couldn't be resolved to an RGD ID |
| `unMatchedGOID.log` | GO term acc not found in RGD |
| `unMatchedPubmed.log` | PubMed reference not in RGD |
| `highLevelGoTerm.log` | skipped: uninformative high-level term |
| `catalyticActivityIPIGoTerm.log` | skipped: IPI on catalytic-activity term |

## Build and run

Java 17. Build with Gradle:

```
./gradlew clean assembleDist
```

The runnable distribution lands under `build/install/go-rat-annotation-pipeline/`.

Production cron entry:

```
src/main/dist/run.sh
```

emails `GoaSummary.log` to the configured recipients when the run finishes.
