import os
import json
import pandas as pd
import numpy as np
import statsmodels.api as sm
import statsmodels.formula.api as smf
import warnings

warnings.filterwarnings("ignore")

def get_clean_prompt_name(filename):
    name = filename.replace('.jsonl', '').lower()
    parts = name.split('-')
    parts.sort()
    if not parts or parts == ['']: return "ZS"
    return " + ".join([p.upper() for p in parts])

def load_data(root_dir):
    records = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if not file.endswith('.jsonl'): continue

            path_parts = os.path.normpath(root).split(os.sep)
            try:
                if 'health' in path_parts:
                    idx = path_parts.index('health')
                    language = path_parts[idx - 1]
                    model_name = path_parts[idx - 2]
                else: continue
            except: continue

            prompt_id = get_clean_prompt_name(file)
            file_path = os.path.join(root, file)

            with open(file_path, 'r') as f:
                for line in f:
                    entry = json.loads(line)
                    func_id = hash(entry.get('fun-code'))
                    refactorings = entry.get('refactorings', [])

                    for ref in refactorings:
                        success = 1 if (
                            ref.get("imp-status") == "score-improved-target-smell-fixed"
                            and
                            ref.get("status") == "all-passed"
                        ) else 0

                        records.append({
                            'Model': model_name,
                            'Language': language,
                            'FunctionID': func_id,
                            'Prompt': prompt_id,
                            'Success': success
                        })
    return pd.DataFrame(records)

def run_combination_analysis(df, model_filter, lang_filter):
    subset = df.copy()
    if model_filter: subset = subset[subset['Model'] == model_filter]
    if lang_filter: subset = subset[subset['Language'] == lang_filter]

    print(f"\n--- Combination Analysis: {model_filter} / {lang_filter} ---")

    categories = sorted(subset['Prompt'].unique())
    if 'ZS' in categories:
        categories.remove('ZS')
        categories.insert(0, 'ZS')

    subset['Prompt'] = pd.Categorical(subset['Prompt'], categories=categories, ordered=True)

    formula = "Success ~ Prompt"

    model = smf.gee(formula,
                    data=subset,
                    groups="FunctionID",
                    family=sm.families.Binomial(),
                    cov_struct=sm.cov_struct.Exchangeable())
    result = model.fit()

    params = result.params
    conf = result.conf_int()
    pvals = result.pvalues

    summary = pd.DataFrame({
        'Log_Odds': params,
        'OR': np.exp(params),
        'P_Value': pvals,
        'CI_Low': np.exp(conf[0]),
        'CI_High': np.exp(conf[1])
    })

    summary.index = summary.index.str.replace(r"^Prompt\[T\.|\]$", "", regex=True)

    print(f"Baseline (ZS) Log-Odds: {summary.loc['Intercept', 'Log_Odds']:.3f}")
    print("\nOdds Ratios (vs Baseline):")

    output = summary.drop('Intercept').sort_values('OR', ascending=False)
    print(output[['OR', 'P_Value', 'CI_Low', 'CI_High']])


if __name__ == "__main__":
    df = load_data("./")
    combos = df[['Model', 'Language']].drop_duplicates().values
    for m, l in combos:
        run_combination_analysis(df, m, l)