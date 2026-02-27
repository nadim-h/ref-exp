from codebleu import calc_codebleu
import sys

def ex_cpp():
    prediction = "int add(x, y) { \n    result = x + y;\n    return result; \n }"
    reference = "int sum(a, b) { \n    total = a + b;\n    return total; \n }"

    weights = (0.1, 0.4, 0.5, 0.0)
    weights = (0.25, 0.25, 0.25, 0.25)

    result = calc_codebleu(
        [reference],
        [prediction],
        lang="cpp",
        weights=weights
    )

    print(result)

def ex_java():
    prediction = "int add(x, y) { \n    result = x + y;\n    return result; \n }"
    reference = "int sum(a, b) { \n    total = a + b;\n    return total; \n }"

    weights = (0.1, 0.4, 0.5, 0.0)
    weights = (0.25, 0.25, 0.25, 0.25)

    result = calc_codebleu(
        [reference],
        [prediction],
        lang="java",
        weights=weights
    )

    print(result)

def ex_python():
    prediction = "def add(x, y):\n    result = x + y\n    return result"
    reference = "def sum(a, b):\n    total = a + b\n    return total"

    weights = (0.1, 0.4, 0.5, 0.0)
    weights = (0.25, 0.25, 0.25, 0.25)

    result = calc_codebleu(
        [reference],
        [prediction],
        lang="python",
        weights=weights
    )

    print(result)

if __name__ == "__main__":
    #ex_cpp()
    #ex_java()
    #ex_python()
    bleu = calc_codebleu([sys.argv[1]],
                         [sys.argv[2]],
                         lang=sys.argv[3],
                         weights=(0.10, 0.40, 0.50, 0.0))
    print(bleu['codebleu'])
