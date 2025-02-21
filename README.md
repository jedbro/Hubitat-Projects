# Pi-hole Virtual Switch for Hubitat

This is an enhanced version of the **Pi-hole Virtual Switch** for Hubitat, originally created by [jedbro](https://github.com/jedbro). This driver allows you to control your Pi-hole instance from Hubitat, enabling on/off toggling, status checks, and automation integration.

## 🚀 Features

- ✅ Enable or disable Pi-hole via a virtual switch in Hubitat.
- 🔄 Check the current status of Pi-hole.
- ⚡ Updated to comply with **Pi-hole v6 API changes**.
- 📦 **Now installable via [Hubitat Package Manager (HPM)](https://github.com/dcmeglio/hubitat-packagemanager).**

## 🔄 Why This Version?

With the release of **Pi-hole v6**, changes in the API required updates to the original driver. This version ensures compatibility with the latest Pi-hole release while also improving code efficiency and maintainability.

## 📦 Installation

### **Hubitat Package Manager (Recommended)**
1. Open **Hubitat Package Manager** in your Hubitat interface.
2. Choose **Install** → **Search by Keywords**.
3. Search for **Pi-hole Virtual Switch**.
4. Click **Install** and follow the on-screen instructions.

### **Manual Installation**
1. Open **Hubitat Web Interface** → **Drivers Code**.
2. Click **+ New Driver** and paste the contents of [`pi-hole-virtual-switch.groovy`](pi-hole-virtual-switch.groovy).
3. Save the driver.
4. Go to **Devices** → **Add Virtual Device** and select **Pi-hole Virtual Switch** as the driver.

## ⚙️ Configuration
1. After installing, open the device page in Hubitat.
2. Enter your **Pi-hole API key** and **server URL** in the device preferences.
3. Save the settings.
4. Use Hubitat automation or dashboards to toggle Pi-hole on/off.

## 🔄 Updates
To update the driver via HPM:
1. Open **Hubitat Package Manager**.
2. Select **Update** → **Check for Updates**.
3. If an update is available, follow the instructions to install.

## 🏆 Credits

This project is based on the original [Pi-hole Virtual Switch](https://github.com/jedbro/Hubitat-Projects/blob/main/Pi-Hole%20Virtual%20Switch/pi-hole-virtual-switch.groovy) by [jedbro](https://github.com/jedbro). Significant improvements have been made to ensure compatibility with Pi-hole v6, enhance performance, and improve usability.

## 📜 License
This project is licensed under the [MIT License](LICENSE).

---