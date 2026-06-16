# Kill Switch

Native Android-App zum Sperren und Entsperren des Internetzugriffs von
Netzwerkgeraeten an einer FRITZ!Box. Die App nutzt ausschliesslich die lokale
TR-064-Schnittstelle. Zugangsdaten werden mit dem Android Keystore
verschluesselt und nicht aus dem Heimnetz gesendet.

## Einrichtung

1. In der FRITZ!Box unter `Heimnetz > Netzwerk > Netzwerkeinstellungen` den
   Zugriff fuer Anwendungen aktivieren.
2. Einen FRITZ!Box-Benutzer mit App-/Einstellungsrechten anlegen. Das Lesen der
   Geraeteliste kann auch dann funktionieren, wenn diesem Benutzer die zum
   Sperren benoetigten Schreibrechte fehlen.
3. Das Android-Geraet mit demselben WLAN verbinden.
4. Kill Switch starten, `[ CONFIG ]` antippen und Router-Adresse, Benutzername
   und Passwort eingeben.

Wenn die Geraeteliste funktioniert, das Sperren aber mit `Action Not Authorized`
oder Fehler `606` abbricht, ist meistens der eingetragene FRITZ!Box-Benutzer der
Grund. In `[ CONFIG ]` kann gezielt ein anderer FRITZ!Box-Benutzer eingetragen
werden. Dieser Benutzer braucht App-Rechte fuer die TR-064-Sperraktion.

Die Schaltflaeche `SPERREN` blockiert den WAN-/Internetzugriff. Das Geraet
bleibt weiterhin mit dem lokalen LAN oder WLAN verbunden. Die App wartet nach
einer Aktion auf die Bestaetigung der FRITZ!Box und zeigt nur einen geaenderten
Status, wenn die Sperre oder Freigabe tatsaechlich aktiv ist.

## Funktionen

- Suche und Favoriten fuer Netzwerkgeraete
- Mehrfachauswahl zum gemeinsamen Sperren oder Freigeben
- Frei einstellbare Sperrdauer in Minuten
- Automatische Freigabe zu einer gewaehlten Uhrzeit
- Wiederherstellung geplanter Freigaben nach einem Neustart

Android kann geplante Aktionen im Energiesparmodus verzoegern. Fuer eine
moeglichst puenktliche Freigabe kann der App der Zugriff auf exakte Alarme
erlaubt werden.

## Build

```powershell
.\gradlew.bat assembleDebug
```
