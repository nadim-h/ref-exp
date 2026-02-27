## JAVA

clojure -X:exec core/health-set :lang :java :template '"zs"' :model $1

clojure -X:exec core/health-set :lang :java :template '"det"' :model $1

clojure -X:exec core/health-set :lang :java :template '"fs"' :model $1

clojure -X:exec core/health-set :lang :java :template '"cot"' :model $1
clojure -X:exec core/health-set :lang :java :template '"cot-fs"' :model $1
clojure -X:exec core/health-set :lang :java :template '"cot-hsp"' :model $1
clojure -X:exec core/health-set :lang :java :template '"cot-det"' :model $1
clojure -X:exec core/health-set :lang :java :template '"cot-hsp-det"' :model $1


clojure -X:exec core/health-set :lang :java :template '"tdb"' :model $1
clojure -X:exec core/health-set :lang :java :template '"tdb-fs"' :model $1
clojure -X:exec core/health-set :lang :java :template '"tdb-hsp"' :model $1
clojure -X:exec core/health-set :lang :java :template '"tdb-det"' :model $1
clojure -X:exec core/health-set :lang :java :template '"tdb-hsp-det"' :model $1

clojure -X:exec core/health-set :lang :java :template '"ps"' :model $1
clojure -X:exec core/health-set :lang :java :template '"ps-hsp"' :model $1
clojure -X:exec core/health-set :lang :java :template '"ps-det"' :model $1
clojure -X:exec core/health-set :lang :java :template '"ps-hsp-det"' :model $1

## PYTHON

clojure -X:exec core/health-set :lang :python  :template '"zs"' :model $1

clojure -X:exec core/health-set :lang :python  :template '"det"' :model $1

clojure -X:exec core/health-set :lang :python  :template '"fs"' :model $1

clojure -X:exec core/health-set :lang :python :template '"cot"' :model $1
clojure -X:exec core/health-set :lang :python :template '"cot-fs"' :model $1
clojure -X:exec core/health-set :lang :python :template '"cot-hsp"' :model $1
clojure -X:exec core/health-set :lang :python :template '"cot-det"' :model $1
clojure -X:exec core/health-set :lang :python :template '"cot-hsp-det"' :model $1

clojure -X:exec core/health-set :lang :python :template '"tdb"' :model $1
clojure -X:exec core/health-set :lang :python :template '"tdb-fs"' :model $1
clojure -X:exec core/health-set :lang :python :template '"tdb-hsp"' :model $1
clojure -X:exec core/health-set :lang :python :template '"tdb-det"' :model $1
clojure -X:exec core/health-set :lang :python :template '"tdb-hsp-det"' :model $1

clojure -X:exec core/health-set :lang :python :template '"ps"' :model $1
clojure -X:exec core/health-set :lang :python :template '"ps-hsp"' :model $1
clojure -X:exec core/health-set :lang :python :template '"ps-det"' :model $1
clojure -X:exec core/health-set :lang :python :template '"ps-hsp-det"' :model $1
