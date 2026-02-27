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
                    try:
                        entry = json.loads(line)
                        func_id = hash(entry.get('fun-code'))
                        refactorings = entry.get('refactorings', [])

                        for ref in refactorings:
                            t_in = ref.get("prompt-tokens", 0)
                            t_out = ref.get("completion-tokens", 0)

                            if pd.isna(t_in) or pd.isna(t_out) or (t_in == 0 and t_out == 0):
                                continue

                            latency = (0.1 * t_in) + (0.9 * t_out)
                            cost_per_1k = ((1.75 * (t_in / 1e6)) + (14.0 * (t_out / 1e6))) * 1000

                            records.append({
                                'Model': model_name,
                                'Language': language,
                                'FunctionID': func_id,
                                'Prompt': prompt_id,
                                'LatencyProxy': latency,
                                'CostPer1k': cost_per_1k
                            })
                    except: continue
    return pd.DataFrame(records)

def run_cost_combination_analysis(df, model_filter, lang_filter, target_metric='CostPer1k'):
    subset = df.copy()
    if model_filter: subset = subset[subset['Model'] == model_filter]
    if lang_filter: subset = subset[subset['Language'] == lang_filter]

    print(f"\n--- {target_metric} Combination Analysis: {model_filter} / {lang_filter} ---")

    categories = sorted(subset['Prompt'].unique())
    if 'ZS' in categories:
        categories.remove('ZS')
        categories.insert(0, 'ZS')

    subset['Prompt'] = pd.Categorical(subset['Prompt'], categories=categories, ordered=True)

    formula = f"{target_metric} ~ Prompt"

    model = smf.gee(formula,
                    data=subset,
                    groups="FunctionID",
                    family=sm.families.Gamma(link=sm.families.links.log()),
                    cov_struct=sm.cov_struct.Exchangeable())
    try:
        result = model.fit()
        if result.params.isna().any():
            raise ValueError("Matrix collapsed")
    except:
        print(f"  [Warning] Exchangeable failed. Falling back to Independence.")
        model = smf.gee(formula,
                        data=subset,
                        groups="FunctionID",
                        family=sm.families.Gamma(link=sm.families.links.log()),
                        cov_struct=sm.cov_struct.Independence())
        result = model.fit(maxiter=100)

    params = result.params
    conf = result.conf_int()
    pvals = result.pvalues

    summary = pd.DataFrame({
        'Cost_Multiplier': np.exp(params),
        'P_Value': pvals,
        'CI_Low': np.exp(conf[0]),
        'CI_High': np.exp(conf[1])
    })

    summary.index = summary.index.str.replace(r"^Prompt\[T\.|\]$", "", regex=True)

    baseline_val = summary.loc['Intercept', 'Cost_Multiplier']
    print(f"Baseline (ZS) Expected {target_metric}: {baseline_val:.2f}")

    output = summary.drop('Intercept').sort_values('Cost_Multiplier', ascending=False)
    print("\nMultipliers (vs Baseline ZS):")
    print(output[['Cost_Multiplier', 'P_Value', 'CI_Low', 'CI_High']])


if __name__ == "__main__":
    df = load_data("./")
    if not df.empty:
        combos = df[['Model', 'Language']].drop_duplicates().values
        for m, l in combos:
            run_cost_combination_analysis(df, m, l, target_metric='CostPer1k')
