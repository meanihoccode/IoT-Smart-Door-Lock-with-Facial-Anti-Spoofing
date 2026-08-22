import os
import urllib.request

def download_file(url, dest_path):
    print(f"Downloading {url}...")
    urllib.request.urlretrieve(url, dest_path)
    print(f"Saved to {dest_path}")

def main():
    # Directories
    os.makedirs("src/model_lib", exist_ok=True)
    os.makedirs("resources/anti_spoof_models", exist_ok=True)
    
    # Files to download
    files = [
        ("https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/master/src/model_lib/MiniFASNet.py", "src/model_lib/MiniFASNet.py"),
        ("https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/master/src/model_lib/MultiScaleAttention.py", "src/model_lib/MultiScaleAttention.py"),
        ("https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/master/src/model_lib/__init__.py", "src/model_lib/__init__.py"),
        ("https://raw.githubusercontent.com/minivision-ai/Silent-Face-Anti-Spoofing/master/resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth", "resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth")
    ]
    
    for url, path in files:
        if not os.path.exists(path):
            download_file(url, path)
        else:
            print(f"File {path} already exists. Skipping.")
            
    print("All files downloaded successfully!")

if __name__ == "__main__":
    main()
