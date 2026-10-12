package de.adv990de.obd2.obd

/**
 * Returns a human-readable description for common DTC codes.
 * Coverage is intentionally partial (a selection of common SAE J2012 codes; no count is stated here, it drifted from 35 to 37).
 * Codes outside this set return "Unknown DTC".
 *
 * A pure function on purpose (it used to be a private helper of [DtcParser]): the fault history stores
 * only the code and resolves the text when it is shown, so the description needs no database column
 * and no dependency-injection binding. The lookup is exact — no trimming, no case folding.
 */
fun dtcDescription(code: String): String = when (code) {
    "P0100" -> "Mass or Volume Air Flow Circuit Malfunction"
    "P0101" -> "Mass or Volume Air Flow Circuit Range/Performance"
    "P0102" -> "Mass or Volume Air Flow Circuit Low Input"
    "P0103" -> "Mass or Volume Air Flow Circuit High Input"
    "P0110" -> "Intake Air Temperature Circuit Malfunction"
    "P0115" -> "Engine Coolant Temperature Circuit Malfunction"
    "P0120" -> "Throttle/Pedal Position Sensor A Circuit Malfunction"
    "P0130" -> "O2 Sensor Circuit Malfunction (Bank 1, Sensor 1)"
    "P0171" -> "System Too Lean (Bank 1)"
    "P0172" -> "System Too Rich (Bank 1)"
    "P0175" -> "System Too Rich (Bank 2)"
    "P0200" -> "Injector Circuit Malfunction"
    "P0300" -> "Random/Multiple Cylinder Misfire Detected"
    "P0301" -> "Cylinder 1 Misfire Detected"
    "P0302" -> "Cylinder 2 Misfire Detected"
    "P0303" -> "Cylinder 3 Misfire Detected"
    "P0304" -> "Cylinder 4 Misfire Detected"
    "P0305" -> "Cylinder 5 Misfire Detected"
    "P0306" -> "Cylinder 6 Misfire Detected"
    "P0325" -> "Knock Sensor 1 Circuit Malfunction (Bank 1)"
    "P0335" -> "Crankshaft Position Sensor A Circuit Malfunction"
    "P0340" -> "Camshaft Position Sensor A Circuit Malfunction (Bank 1)"
    "P0400" -> "Exhaust Gas Recirculation Flow Malfunction"
    "P0401" -> "Exhaust Gas Recirculation Flow Insufficient"
    "P0420" -> "Catalyst System Efficiency Below Threshold (Bank 1)"
    "P0421" -> "Warm Up Catalyst Efficiency Below Threshold (Bank 1)"
    "P0430" -> "Catalyst System Efficiency Below Threshold (Bank 2)"
    "P0440" -> "Evaporative Emission Control System Malfunction"
    "P0442" -> "Evaporative Emission Control System Leak Detected (Small)"
    "P0455" -> "Evaporative Emission Control System Leak Detected (Gross)"
    "P0500" -> "Vehicle Speed Sensor Malfunction"
    "P0505" -> "Idle Control System Malfunction"
    "P0600" -> "Serial Communication Link Malfunction"
    "P0700" -> "Transmission Control System Malfunction"
    "P0711" -> "Transmission Fluid Temperature Sensor Circuit Range/Performance"
    "P0730" -> "Incorrect Gear Ratio"
    "P0740" -> "Torque Converter Clutch Circuit Malfunction"
    else -> "Unknown DTC"
}
