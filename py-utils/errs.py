import argparse
import json
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns
from pathlib import Path

# --- Configuration ---
# Map long error names to short acronyms for the table headers
ERROR_ACRONYMS = {
    "undefined-symbol": "Sym",
    "incompatible-types": "Type",
    "missing-semicolon": "Semi",
    "unreported-exception": "Excep",
    "duplicate-method": "Dup M",
    "duplicate-variable": "Dup V",
    "expression-must-be-final": "Final",
    "unreachable-statement": "Unreach",
    "illegal-start": "Ill Start",
    "array-index-out-of-bounds": "ArrIdx",
    "string-index-out-of-bounds": "StrIdx",
    "null-pointer": "NPE",
    "timeout": "Time",
    "generic-runtime": "Run",
    "no-such-element": "NoElem"
}

def prettify_model_name(name: str) -> str:
    """Standardizes model names (e.g., 'cot-hsp' -> 'CoT-HSP')."""
    name_lower = name.lower()
    if name_lower in ["zs", "baseline"]: return "ZS"
    if name_lower in ["fs", "baseline-nshot"]: return "FS"
    
    parts = name.split('-')
    new_parts = []
    for part in parts:
        if part in ["cot", "tdb", "ps", "sb"]: new_parts.append(part.upper())
        elif part == "hs": new_parts.append("HSP")
        elif part == "det": new_parts.append("DET")
        else: new_parts.append(part.capitalize())
    return "-".join(new_parts)

def parse_files(directory: Path) -> list[dict]:
    """Parses all .jsonl files and extracts raw failure counts for ALL categories."""
    records = []
    files = sorted(list(directory.glob("*.jsonl")))
    
    if not files:
        print("No .jsonl files found.")
        return []

    print(f"Parsing {len(files)} files...")
    for f in files:
        model_name = prettify_model_name(f.stem)
        try:
            with open(f, 'r', encoding='utf-8') as file:
                for line in file:
                    if not line.strip(): continue
                    try:
                        entry = json.loads(line)
                        category = entry[0]
                        data = entry[1]

                        # Extract counts
                        meta = data.get('meta', {})
                        total_attempts = meta.get('total-attempts', 0)

                        errs = data.get('errs', {})
                        comp_errs = errs.get('compiler-sub-categories', {})
                        run_errs = errs.get('runtime-sub-categories', {})

                        # Flatten for DataFrame
                        # We store one row per (Model, Category)
                        record = {
                            'model': model_name,
                            'category': category,
                            'total_attempts': total_attempts,
                            'compile_total': sum(comp_errs.values()),
                            'runtime_total': sum(run_errs.values()),
                            'raw_comp_errs': comp_errs, # Keep dict for flexible counting later
                            'raw_run_errs': run_errs
                        }
                        records.append(record)
                    except json.JSONDecodeError:
                        continue
        except Exception as e:
            print(f"Error reading {f.name}: {e}")

    return records

def get_top_errors_for_category(df_cat, error_type_col='raw_comp_errs', top_n=3):
    """
    Finds the top N most frequent errors within a specific category across all models.
    """
    agg_counts = {}
    for err_dict in df_cat[error_type_col]:
        for k, v in err_dict.items():
            agg_counts[k] = agg_counts.get(k, 0) + v
            
    sorted_errs = sorted(agg_counts.items(), key=lambda x: x[1], reverse=True)
    return [k for k, v in sorted_errs[:top_n]]

def generate_smart_latex(df: pd.DataFrame, filename: str):
    """
    Generates a grouped longtable.
    Groups by Category -> Shows breakdown of failures for each model.
    """
    if df.empty: return

    # Define Category Order (Put 'all' last or first?)
    # Usually specific smells first, then 'all' summary.
    categories = sorted(df['category'].unique())
    if 'all' in categories:
        categories.remove('all')
        categories.append('all') # Move 'all' to end

    latex = []
    latex.append(r"\documentclass{article}")
    latex.append(r"\usepackage{booktabs}")
    latex.append(r"\usepackage{multirow}")
    latex.append(r"\usepackage{longtable}")
    latex.append(r"\usepackage{geometry}")
    latex.append(r"\geometry{margin=1in}")
    latex.append(r"\begin{document}")

    latex.append(r"\begin{longtable}{l|c|cccc|ccc}")
    latex.append(r"\caption{Raw Failure Counts by Category (Top Reasons)} \\")
    latex.append(r"\label{tab:failures-breakdown} \\")
    
    for cat in categories:
        # Filter data for this category
        cat_df = df[df['category'] == cat].copy()

        # Sort models (ZS last)
        cat_df['sort_key'] = cat_df['model'].apply(lambda x: 1 if x == "ZS" else 0)
        cat_df.sort_values(by=['sort_key', 'model'], inplace=True)

        # Identify Top Errors for this specific category
        top_comp = get_top_errors_for_category(cat_df, 'raw_comp_errs', top_n=3)
        top_run = get_top_errors_for_category(cat_df, 'raw_run_errs', top_n=2)

        # --- Header for this Category ---
        latex.append(r"\toprule")
        latex.append(f"\\multicolumn{{9}}{{c}}{{\\textbf{{Category: {cat}}}}} \\\\")
        latex.append(r"\midrule")
        
        # Sub-header
        # Model | Attempts | Comp Total | C1 | C2 | C3 | Run Total | R1 | R2
        header_row_1 = [r"\textbf{Model}", r"\textbf{Atmpt}"]
        header_row_1.append(r"\multicolumn{4}{c|}{\textbf{Compilation Failures}}")
        header_row_1.append(r"\multicolumn{3}{c}{\textbf{Runtime Failures}}")
        latex.append(" & ".join(header_row_1) + r" \\")

        header_row_2 = ["", ""]
        header_row_2.append(r"\textbf{Tot}")
        for err in top_comp:
            short = ERROR_ACRONYMS.get(err, err[:4].title())
            header_row_2.append(f"\\textit{{{short}}}")
        # Fill empty columns if fewer than 3 errors found
        while len(header_row_2) < 6: header_row_2.append("-")

        header_row_2.append(r"\textbf{Tot}")
        for err in top_run:
            short = ERROR_ACRONYMS.get(err, err[:4].title())
            header_row_2.append(f"\\textit{{{short}}}")
        # Fill empty if fewer than 2
        while len(header_row_2) < 9: header_row_2.append("-")

        latex.append(" & ".join(header_row_2) + r" \\ \midrule")
        
        # --- Data Rows ---
        for _, row in cat_df.iterrows():
            line = [row['model'], str(row['total_attempts'])]
            
            # Compilation
            line.append(f"\\textbf{{{row['compile_total']}}}")
            for err in top_comp:
                val = row['raw_comp_errs'].get(err, 0)
                line.append(str(val) if val > 0 else r"\textcolor{gray}{0}")
            while len(line) < 6: line.append("-")
            
            # Runtime
            line.append(f"\\textbf{{{row['runtime_total']}}}")
            for err in top_run:
                val = row['raw_run_errs'].get(err, 0)
                line.append(str(val) if val > 0 else r"\textcolor{gray}{0}")
            while len(line) < 9: line.append("-")
            
            latex.append(" & ".join(line) + r" \\")
            
        latex.append(r"\addlinespace[1em]") # Space between categories

    latex.append(r"\bottomrule")
    latex.append(r"\end{longtable}")
    latex.append(r"\end{document}")

    with open(filename, 'w', encoding='utf-8') as f:
        f.write("\n".join(latex))
    print(f"LaTeX table saved to {filename}")

def generate_plot(df: pd.DataFrame, filename: str):
    """
    Generates a visual stacked bar plot of failure types.
    Groups small errors into 'Other' to keep the plot clean.
    """
    if df.empty: return

    # Expand the dictionaries into rows for plotting
    plot_data = []
    for _, row in df.iterrows():
        # Add Compiler Errors
        for err, count in row['raw_comp_errs'].items():
            plot_data.append({
                'Model': row['model'],
                'Category': row['category'],
                'Type': 'Compiler',
                'Error': err,
                'Count': count
            })
        # Add Runtime Errors
        for err, count in row['raw_run_errs'].items():
            plot_data.append({
                'Model': row['model'],
                'Category': row['category'],
                'Type': 'Runtime',
                'Error': err,
                'Count': count
            })

    pdf = pd.DataFrame(plot_data)
    if pdf.empty: return

    # Filter out 'all' category for the plot to avoid duplication
    pdf = pdf[pdf['Category'] != 'all']

    # Keep only top 6 errors globally, label others as 'Other'
    top_errors = pdf.groupby('Error')['Count'].sum().nlargest(6).index
    pdf['Error_Grouped'] = pdf['Error'].apply(lambda x: x if x in top_errors else 'Other')

    # Setup FacetGrid
    g = sns.FacetGrid(pdf, col="Category", col_wrap=3, sharey=True, height=4, aspect=1.2)

    def stacked_bar(data, **kwargs):
        # Pivot for stacked bar
        pivot = data.pivot_table(index='Model', columns='Error_Grouped', values='Count', aggfunc='sum', fill_value=0)
        pivot.plot(kind='bar', stacked=True, ax=plt.gca(), width=0.8, colormap='tab10')

    g.map_dataframe(stacked_bar)
    g.add_legend()
    g.set_xticklabels(rotation=45, ha='right')
    g.set_titles("{col_name}")
    g.set_axis_labels("Model", "Failure Count")

    plt.tight_layout()
    plt.savefig(filename)
    print(f"Plot saved to {filename}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=str, default=".", help="Directory with .jsonl files")
    parser.add_argument("--out_tex", type=str, default="smart_failures.tex")
    parser.add_argument("--out_plot", type=str, default="smart_failures.png")
    args = parser.parse_args()

    data_dir = Path(args.dir)
    raw_data = parse_files(data_dir)
    
    if not raw_data: return

    df = pd.DataFrame(raw_data)
    
    # Generate Table
    generate_smart_latex(df, args.out_tex)
    
    # Generate Plot
    generate_plot(df, args.out_plot)

if __name__ == "__main__":
    main()
