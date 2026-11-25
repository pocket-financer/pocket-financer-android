import argparse
import json
import os
import random
import subprocess
import time
from pathlib import Path

def detect_devices():
    """Run `adb devices` and parse connected device serial numbers."""
    adb_path = r"C:\Users\manis\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    if not os.path.exists(adb_path):
        adb_path = "adb"  # fallback to PATH
    
    try:
        output = subprocess.check_output([adb_path, "devices"], text=True)
    except Exception as e:
        print(f"Error running adb: {e}")
        return [], adb_path
        
    devices = []
    for line in output.strip().splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices, adb_path

def shell_escape(s: str) -> str:
    """Escape a string to be safely wrapped in single quotes in shell."""
    return "'" + s.replace("'", "'\\''") + "'"

def main():
    parser = argparse.ArgumentParser(
        description="Inject SMS fixtures; connected emulator inboxes will be cleared."
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=(
            Path(__file__).resolve().parent.parent
            / "pocket-financer-intelligence" / "DATA" / "extraction_ds.jsonl"
        ),
        help="JSONL dataset (default: the sibling pocket-financer-intelligence checkout)",
    )
    dataset_path = parser.parse_args().dataset.expanduser()
    if not dataset_path.is_file():
        print(f"Error: Dataset not found at {dataset_path}")
        return
        
    devices, adb_path = detect_devices()
    if not devices:
        print("Error: No connected Android devices detected. Make sure your emulator or phone is connected via ADB.")
        return
        
    print(f"Detected connected devices: {devices}")
    
    # Read SMS entries
    sms_entries = []
    with open(dataset_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
                if "sms" in entry and "sender" in entry:
                    sms_entries.append(entry)
            except Exception as e:
                print(f"Failed to parse line: {e}")
                
    if not sms_entries:
        print("No valid SMS entries found in the dataset.")
        return
        
    print(f"Loaded {len(sms_entries)} SMS messages from dataset.")
    
    # Limit to 20 messages (preferring 10 transactional and 10 promotional for testing)
    transactional = [e for e in sms_entries if e.get("expected") != "null"]
    promotional = [e for e in sms_entries if e.get("expected") == "null"]
    selected_entries = transactional[:10] + promotional[:10]
    if len(selected_entries) < 20:
        selected_entries = sms_entries[:20]
    
    random.shuffle(selected_entries)
    sms_entries = selected_entries
        
    print(f"Selected {len(sms_entries)} SMS messages for injection (10 transactional, 10 promotional).")
    
    # We want to distribute these SMS messages across the last 14 days
    # so they fall inside the onboarding 14-day sync window.
    now_ms = int(time.time() * 1000)
    fourteen_days_ms = 14 * 24 * 60 * 60 * 1000
    start_time_ms = now_ms - fourteen_days_ms
    
    # Distribute uniformly
    interval = fourteen_days_ms // len(sms_entries)
    
    # Iterate and inject per device
    for device in devices:
        print(f"\n==========================================")
        print(f"Targeting device: {device}")
        print(f"==========================================")
        
        commands = []
        commands.append("appops set com.android.shell WRITE_SMS allow")
        if device.startswith("emulator-"):
            commands.append("echo 'Clearing emulator SMS inbox...'")
            commands.append("content delete --uri content://sms")
        else:
            commands.append("echo 'Physical device detected. Skipping full SMS deletion to protect actual messages.'")

        total_count = len(sms_entries)
        for idx, entry in enumerate(sms_entries):
            timestamp = start_time_ms + (idx * interval)
            sender = entry["sender"].replace(":", " ")
            body = entry["sms"].replace(":", " ").replace("\n", " ").replace("\r", " ")
            
            escaped_sender = shell_escape(sender)
            escaped_body = shell_escape(body)
            
            commands.append(f"echo 'Progress: {idx+1}/{total_count}'")
            cmd = (f"content insert --uri content://sms/inbox "
                   f"--bind address:s:{escaped_sender} "
                   f"--bind body:s:{escaped_body} "
                   f"--bind date:l:{timestamp} "
                   f"--bind read:i:1")
            commands.append(cmd)
             
        # Force-stop messaging apps to trigger UI refresh
        commands.append("am force-stop com.google.android.apps.messaging")
        commands.append("am force-stop com.android.messaging")
            
        shell_script_content = "\n".join(commands) + "\n"
        
        # Write temporary file on host
        temp_script_path = f"temp_inject_{device.replace('.', '_').replace('-', '_')}.sh"
        with open(temp_script_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(shell_script_content)
            
        try:
            # Push script to device
            remote_path = "/data/local/tmp/inject_sms.sh"
            print("Pushing injection script to device...")
            subprocess.run([adb_path, "-s", device, "push", temp_script_path, remote_path], check=True, stdout=subprocess.DEVNULL)
            
            # Execute script on device and stream stdout in real-time
            print("Running injection script...")
            process = subprocess.Popen(
                [adb_path, "-s", device, "shell", "sh", remote_path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1
            )
            
            for line in process.stdout:
                line_str = line.strip()
                if line_str:
                    print(f"[{device}] {line_str}")
                    
            process.wait()
            
            # Clean up script on device
            subprocess.run([adb_path, "-s", device, "shell", "rm", remote_path], check=True, stdout=subprocess.DEVNULL)
            print(f"Successfully finished injection for {device}!")
            
        except Exception as e:
            print(f"Error during injection on {device}: {e}")
        finally:
            if os.path.exists(temp_script_path):
                os.remove(temp_script_path)

if __name__ == "__main__":
    main()
