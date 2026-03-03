import argparse
import json
from pathlib import Path
import pandas as pd
import numpy as np

SMELL_MAPPING = {
    "Large-Method": "LM",
    "Complex-Conditional": "CC",
    "Bumpy-Road-Ahead": "BR",
    "Deep--Nested-Complexity": "NC",
    "Complex-Method": "CM"
}

SMELL_ORDER = ["LM", "CC", "BR", "NC", "CM"]

def get_model_name(name: str) -> str:
    name = name.lower()
    parts = name.split('-')
    new_parts = []
    for part in parts:
        match part:
            case "cot": new_parts.append("CoT")
            case "zs": new_parts.append("ZS")
            case "fs": new_parts.append("FS")
            case "tdb": new_parts.append("TDB")
            case "ps": new_parts.append("PS")
            case "hsp": new_parts.append("HSP")
            case "det": new_parts.append("DET")
            case "sb": new_parts.append("SB")
            case _ : new_parts.append(part.upper())
    return "-".join(new_parts)

def get_lang(lang: str) -> str:
    match lang:
        case "java": return "Java"
        case "python": return "Python"
        case _: return lang

def get_model(model: str) -> str:
    match model:
        case "qwen3": return "Qwen3-Coder-30B-A3B"
        case "gpt-oss120": return "GPT-OSS-120B"
        case _: return model

def get_significance_stars(p_val):
    """Returns LaTeX string for significance stars based on p-value."""
    if pd.isna(p_val) or p_val is None:
        return ""
    if p_val < 0.01:
        return r"$^{**}$"
    if p_val < 0.05:
        return r"$^{*}$"
    return ""

def format_val_with_ci(mean, ci, p_val, col_name, max_vals, decimals=1):
    """Formats value as 'Mean +/- CI' with optional significance stars."""
    if pd.isna(mean): return "-"

    mean_str = f"{mean:.{decimals}f}"
    #ci_str = f"{ci:.{decimals}f}" if pd.notna(ci) else "0.0"

    #stars = get_significance_stars(p_val)

    # Format: Mean \tiny{\pm CI} STARS
    #val_str = f"{mean_str} \\tiny{{$\\pm${ci_str}}}{stars}"
    val_str = f"{mean_str}"

    # Bold if Mean is max
    if mean >= max_vals[col_name] - 0.001:
        return f"\\textbf{{{val_str}}}"

    return val_str

def format_simple_val(val, col_name, max_vals, decimals=2, is_int=False):
    if pd.isna(val): return "-"

    if is_int:
        num_str = f"{int(val)}"
    else:
        num_str = f"{val:.{decimals}f}"

    if val >= max_vals[col_name] - 0.001:
        return f"\\textbf{{{num_str}}}"
    return num_str

def parse_jsonl_file(file_path: Path) -> dict|None:
    model_raw_name = file_path.stem
    model_pretty = get_model_name(model_raw_name)
    if 'HSP' in model_pretty: return None

    data_store = {
        'model': model_pretty,
    }

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            for line in f:
                if not line.strip(): continue
                try:
                    entry = json.loads(line)
                    category = entry[0]
                    stats = entry[1]

                    metrics = stats.get('metrics', {})
                    exec_stats = stats.get('exec', {})
                    tokens = stats.get('tokens', {})
                    quality = stats.get('passed-quality', {})

                    # New Stats Fields
                    p_values = stats.get('p-value', {})

                    if category == 'all':
                        # --- Performance ---
                        data_store['perf_comp'] = 100.0 - exec_stats.get('compile-fail-rate', 0.0)

                        # New Format
                        data_store['perf_pass_mean'] = metrics['pass-rate'].get('mean', 0.0)
                        data_store['perf_pass_ci'] = metrics['pass-rate'].get('ci', 0.0)
                        data_store['perf_imp_mean'] = metrics['imp-rate'].get('mean', 0.0)
                        data_store['perf_imp_ci'] = metrics['imp-rate'].get('ci', 0.0)

                        # P-Values
                        data_store['stat_pval_pass'] = p_values.get('pass', None)
                        data_store['stat_pval_imp'] = p_values.get('imp', None)

                        # --- CodeHealth ---
                        data_store['ch_imp'] = quality.get('ch-improvement-rate', 0.0)
                        data_store['ch_unchanged'] = quality.get('ch-unchanged-rate', 0.0)
                        data_store['ch_worse'] = quality.get('ch-worse-rate', 0.0)

                        # --- Tokens ---
                        data_store['tok_total'] = tokens.get('avg-total', 0)
                        data_store['tok_in'] = tokens.get('avg-prompt', 0)
                        data_store['tok_out'] = tokens.get('avg-completion', 0)

                    # --- Per-Smell Stats ---
                    elif category in SMELL_MAPPING:
                        short_code = SMELL_MAPPING[category]
                        if 'pass-rate' in metrics and isinstance(metrics['pass-rate'], dict):
                             data_store[f'{short_code}_pass'] = metrics['pass-rate'].get('mean', 0.0)
                             data_store[f'{short_code}_imp'] = metrics['imp-rate'].get('mean', 0.0)
                        else:
                             data_store[f'{short_code}_pass'] = metrics.get('pass-at-1', 0.0)
                             data_store[f'{short_code}_imp'] = metrics.get('imp-at-1', 0.0)

                except (json.JSONDecodeError, IndexError):
                    continue
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
        return None

    return data_store

def gen_latex_table(df: pd.DataFrame, filename: str, model: str, lang: str):

    df.sort_values(by='perf_imp_mean', ascending=False, inplace=True)

    zs_row = df[df['model'] == 'ZS']
    non_zs = df[df['model'] != 'ZS']
    df = pd.concat([non_zs, zs_row])

    max_vals = {}
    cols_to_max = {
        'perf_comp': 'perf_comp',
        'perf_pass_mean': 'perf_pass_mean',
        'perf_imp_mean': 'perf_imp_mean',
        'ch_imp': 'ch_imp',
        'ch_unchanged': 'ch_unchanged',
        'ch_worse': 'ch_worse',
        'tok_total': 'tok_total',
        'tok_in': 'tok_in',
        'tok_out': 'tok_out'
    }
    for smell in SMELL_ORDER:
        cols_to_max[f'{smell}_pass'] = f'{smell}_pass'
        cols_to_max[f'{smell}_imp'] = f'{smell}_imp'

    for col_key, df_col in cols_to_max.items():
        if df_col in df.columns:
            max_vals[col_key] = df[df_col].max()

    latex = []
    latex.append(r"\begin{table*}[h]")
    latex.append(r"\centering")
    latex.append(fr"\caption{{{get_lang(lang)} {get_model(model)}. All values are the mean.}}")
    latex.append(fr"\label{{tab:{lang}-{model}}}")
    latex.append(r"\tiny")
    #latex.append(r"\setlength{\tabcolsep}{4pt}")

    # --- Column Definition ---
    # l | ccc | ccc | ccc | ... smells
    # Prompt(1)
    # Perf(3): Comp, Pass, Imp
    # CH(3): ^, >, v
    # Tok(3): Tot, In, Out
    # Smells(2*5=10)
    col_def = "l|ccc|ccc|ccc|" + "|".join(["cc"] * len(SMELL_ORDER))

    latex.append(f"\\begin{{tabular}}{{{col_def}}}")
    latex.append(r"\toprule")

    # --- Header Row 1 ---
    # Prompt | Perf(3) | CH(3) | Tok(3) | Smells...
    row1 = [r"\multirow{2}{*}{\textbf{Prompt}}"]
    row1.append(r"\multicolumn{3}{c|}{\textbf{Perf.}}")
    row1.append(r"\multicolumn{3}{c|}{\textbf{CodeHealth}}")
    row1.append(r"\multicolumn{3}{c|}{\textbf{Tokens}}")

    for smell in SMELL_ORDER:
        row1.append(f"\\multicolumn{{2}}{{c}}{{\\textbf{{{smell}}}}}")

    latex.append(" & ".join(row1) + r" \\")

    # --- Cline Rules ---
    # Prompt(1)
    # Perf(2-4) | CH(5-7) | Tok(8-10) | Smells(11...)
    clines = []
    clines.append(r"\cline{2-10}")

    start_idx = 11
    for i in range(len(SMELL_ORDER)):
        end_idx = start_idx + 1
        clines.append(f"\\cmidrule(lr){{{start_idx}-{end_idx}}}")
        start_idx += 2

    latex.append(" ".join(clines))

    # --- Header Row 2 ---
    row2 = [""]

    # Perf (3)
    row2.extend([r"\textbf{Comp.}", r"\textbf{Pass}", r"\textbf{Imp}"])

    # CodeHealth (3)
    row2.extend([r"\textbf{$\uparrow$}", r"\textbf{$\rightarrow$}", r"\textbf{$\downarrow$}"])

    # Tokens (3)
    row2.extend([r"\textbf{Total}", r"\textbf{In}", r"\textbf{Out}"])

    # Smells
    for _ in SMELL_ORDER:
        row2.extend([r"\textbf{Pass}", r"\textbf{Imp}"])

    latex.append(" & ".join(row2) + r" \\")
    latex.append(r"\midrule")

    # --- Data Rows ---
    for idx, row in df.iterrows():
        if row['model'] == 'ZS' and len(df) > 1:
            latex.append(r"\addlinespace")

        line_items = []
        line_items.append(row['model'])

        # 1. Perf
        line_items.append(format_simple_val(row['perf_comp'], 'perf_comp', max_vals))

        # Pass (Mean +/- CI + Stars)
        line_items.append(format_val_with_ci(
            row.get('perf_pass_mean'),
            row.get('perf_pass_ci'),
            row.get('stat_pval_pass'),
            'perf_pass_mean',
            max_vals
        ))

        # Imp (Mean +/- CI + Stars)
        line_items.append(format_val_with_ci(
            row.get('perf_imp_mean'),
            row.get('perf_imp_ci'),
            row.get('stat_pval_imp'),
            'perf_imp_mean',
            max_vals
        ))

        # 2. CodeHealth
        line_items.append(format_simple_val(row['ch_imp'], 'ch_imp', max_vals))
        line_items.append(format_simple_val(row['ch_unchanged'], 'ch_unchanged', max_vals))
        line_items.append(format_simple_val(row['ch_worse'], 'ch_worse', max_vals))

        # 3. Tokens
        line_items.append(format_simple_val(row['tok_total'], 'tok_total', max_vals, is_int=True))
        line_items.append(format_simple_val(row['tok_in'], 'tok_in', max_vals, is_int=True))
        line_items.append(format_simple_val(row['tok_out'], 'tok_out', max_vals, is_int=True))

        # 4. Smells
        for smell in SMELL_ORDER:
            p_col = f'{smell}_pass'
            i_col = f'{smell}_imp'
            line_items.append(format_simple_val(row.get(p_col, 0), p_col, max_vals, decimals=1))
            line_items.append(format_simple_val(row.get(i_col, 0), i_col, max_vals, decimals=1))

        latex.append(" & ".join(line_items) + r" \\")

    latex.append(r"\bottomrule")
    latex.append(r"\end{tabular}")
    latex.append(r"\end{table*}")

    with open(filename, 'w', encoding='utf-8') as f:
        f.write("\n".join(latex))
    print(f"Table saved to {filename}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=str, default=".", help="Directory with .jsonl files")
    parser.add_argument("--out", type=str, default="formatted_table.tex")
    args = parser.parse_args()

    data_dir = Path(args.dir)

    try:
        model = data_dir.parts[-3]
        lang = data_dir.parts[-2]
    except IndexError:
        model = "Model"
        lang = "Language"

    all_data = []

    files = sorted(list(data_dir.glob("*.jsonl")))
    if not files:
        print("No .jsonl files found.")
        return

    print(f"Processing {len(files)} files...")
    for f in files:
        record = parse_jsonl_file(f)
        if record:
            all_data.append(record)

    df = pd.DataFrame(all_data)
    gen_latex_table(df, args.out, model, lang)

if __name__ == "__main__":
    main()
