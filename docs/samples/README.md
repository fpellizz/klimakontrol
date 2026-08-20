# Campioni

Qui vanno le risposte reali del cloud e dei dispositivi, **mascherate** con
`klimakontrol.session.mask()` prima di essere salvate.

Servono a due cose: adeguare il parsing a quello che il server manda davvero (oggi la forma
delle risposte è dedotta dal codice dell'SDK, non osservata), e diventare fixture dei test.

Convenzione dei nomi: `<comando>-<esito>.json`, per esempio `login-ok.json`,
`sdkcontrol-get-ok.json`, `energy-day-ok.json`.

I `.json` di questa cartella sono esclusi dal repo tranne questo README: contengono dati
dell'impianto. Committali solo dopo aver controllato che il mascheramento abbia coperto tutto.
