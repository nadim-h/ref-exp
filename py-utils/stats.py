import os
import json
import pandas as pd
import numpy as np
import statsmodels.api as sm
import statsmodels.formula.api as smf
import warnings

warnings.filterwarnings("ignore")

def parse_prompt_components(filename):
    name = filename.replace('.jsonl', '').lower()
    parts = name.split('-')
    return {
        'has_FS': 1 if 'fs' in parts else 0,
        'has_CoT': 1 if 'cot' in parts else 0,
        'has_PS': 1 if 'ps' in parts else 0,
        'has_TDB': 1 if 'tdb' in parts else 0,
        'has_HSP': 1 if 'hsp' in parts else 0,
        'has_DET': 1 if 'det' in parts else 0
    }

def load_data(root_dir):
    records = []
    for root, _dirs, files in os.walk(root_dir):
        for file in files:
            if not file.endswith('.jsonl'): continue
            if 'hsp' in file: continue

            path_parts = os.path.normpath(root).split(os.sep)
            try:
                if 'health' in path_parts:
                    idx = path_parts.index('health')
                    language = path_parts[idx - 1]
                    model_name = path_parts[idx - 2]
                else: continue
            except: continue

            prompt_features = parse_prompt_components(file)
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

                        row = {
                            'Model': model_name,
                            'Language': language,
                            'FunctionID': func_id,
                            'Success': success
                        }
                        row.update(prompt_features)
                        records.append(row)
    return pd.DataFrame(records)

def run_gee_analysis(df, model_filter=None, lang_filter=None):
    subset = df.copy()
    if model_filter: subset = subset[subset['Model'] == model_filter]
    if lang_filter: subset = subset[subset['Language'] == lang_filter]

    print(f"\n--- Analysis: {model_filter}/{lang_filter} ---")
    print(f"Data points: {len(subset)}")

    features = ['has_FS', 'has_CoT', 'has_PS', 'has_TDB', 'has_HSP', 'has_DET']
    for col in features:
        subset[col] = np.where(subset[col] == 1, 1, 0)

    active = [f for f in features if subset[f].nunique() > 1]
    if not active: return

    formula = "Success ~ " + " + ".join(active)

    model = smf.gee(formula,
                    data=subset,
                    groups="FunctionID",
                    family=sm.families.Binomial(),
                    cov_struct=sm.cov_struct.Exchangeable())
    result = model.fit()

    if result.params.isna().any():
            print(f"  [Warning] Exchangeable failed. Falling back to Independence.")
            model = smf.gee(formula,
                            data=subset,
                            groups="FunctionID",
                            family=sm.families.Binomial(),
                            cov_struct=sm.cov_struct.Independence())
            result = model.fit(maxiter=100)

    #print(result.summary())
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

    output = summary.drop('Intercept').sort_values('OR', ascending=False)
    print(f"Baseline (ZS) Log-Odds: {summary.loc['Intercept', 'Log_Odds']:.3f}")
    print("\nOdds Ratios (vs Baseline):")
    print(output[['OR', 'P_Value', 'CI_Low', 'CI_High']])

if __name__ == "__main__":
    df = load_data("./")
    if not df.empty:
        combos = df[['Model', 'Language']].drop_duplicates().values
        for m, l in combos:
            run_gee_analysis(df, m, l)
