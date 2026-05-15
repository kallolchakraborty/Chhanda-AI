import re
import os

def remove_comments(text):
    # Remove multi-line comments
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
    # Remove single-line comments
    text = re.sub(r'//.*', '', text)
    # Remove consecutive empty lines
    text = re.sub(r'\n\s*\n', '\n\n', text)
    return text

files_to_clean = [
    "app/src/main/java/com/chhanda/ai/data/inference/ChhandaServer.kt",
    "app/src/main/java/com/chhanda/ai/domain/usecase/SendMessageUseCase.kt",
    "app/src/main/java/com/chhanda/ai/data/inference/ServerTemplateProvider.kt",
    "app/src/main/java/com/chhanda/ai/presentation/viewmodel/ChatViewModel.kt",
    "app/src/main/java/com/chhanda/ai/data/repository/SettingsRepository.kt",
    "app/src/main/java/com/chhanda/ai/presentation/viewmodel/SystemViewModel.kt"
]

for file_path in files_to_clean:
    if os.path.exists(file_path):
        with open(file_path, 'r') as f:
            content = f.read()
        cleaned = remove_comments(content)
        with open(file_path, 'w') as f:
            f.write(cleaned)
        print(f"Cleaned {file_path}")
