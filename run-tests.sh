## JAVA

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"zs"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"fs"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"cot"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"cot-fs"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"cot-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"cot-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"cot-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"tdb"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"tdb-fs"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"tdb-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"tdb-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"tdb-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"ps"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"ps-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"ps-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :java :tests '"java_tests.jsonl"' :tasks '"java_tasks.jsonl"' :template '"ps-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*

## PYTHON

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"zs"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"fs"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"cot"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"cot-fs"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"cot-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"cot-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"cot-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"tdb"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"tdb-fs"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"tdb-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"tdb-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"tdb-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*

clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"ps"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"ps-hsp"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"ps-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
clojure -X:exec core/test-set :lang :python :tests '"python_tests.jsonl"' :tasks '"python_tasks.jsonl"' :template '"ps-hsp-det"' :model $1
rm -rf /home/jovyan/exp_runs/*
