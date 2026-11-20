# ServiceOps Lab

ServiceOps suit une demande d’intervention depuis sa création jusqu’à sa clôture. Le web Angular propose un Kanban. Le client Ionic/Capacitor conserve les interventions chargées et les brouillons hors ligne. Les deux clients utilisent le même contrat HTTP et peuvent basculer entre les backends Java et Kotlin.

## Démonstration locale

Prérequis pour le parcours navigateur. JDK 27, Docker Compose, Python 3 et Node.js 22.12 ou supérieur dans la série 22. Vérifie que `JAVA_HOME` pointe sur le JDK 27 utilisé par IntelliJ.

Si `.env` est absent, copie `.env.example` vers `.env` et renseigne `POSTGRES_PASSWORD` avec une valeur aléatoire locale. Compose conserve les données PostgreSQL dans des volumes nommés.

```sh
./mvnw package
npm ci
npm run build
python3 infra/local.py prepare
```

Lance ces commandes dans quatre terminaux et attends que chaque application ait démarré. Les variantes utilisent des bases et des files distinctes.

```sh
python3 infra/local.py run java notifications
python3 infra/local.py run java operations
python3 infra/local.py run kotlin notifications
python3 infra/local.py run kotlin operations
```

Pour travailler sur les clients depuis VS Code, lance `npm run web` et `npm run mobile` dans deux terminaux. Ouvre le [web](http://localhost:4200) ou [Ionic web](http://localhost:8100). Pour tester un rechargement à froid sans réseau, sers la version construite de la même manière que Playwright.

| Service | URL |
| --- | --- |
| Java | [Swagger](http://127.0.0.1:8080/swagger-ui.html) · [Health](http://127.0.0.1:8080/actuator/health) |
| Kotlin | [Swagger](http://127.0.0.1:8081/swagger-ui.html) · [Health](http://127.0.0.1:8081/actuator/health) |
| Keycloak | [Realm ServiceOps](http://localhost:8180/realms/serviceops/.well-known/openid-configuration) |
| RabbitMQ | [Console locale](http://127.0.0.1:15672) |

Les comptes fictifs `requester`, `manager`, `technician` et `technician2` utilisent le mot de passe de démonstration `serviceops-demo` dans le realm local. Change de rôle en te déconnectant. Choisis Java ou Kotlin dans la barre du client. Pour la console RabbitMQ, utilise `serviceops` et le mot de passe local de `.env`.

### Connexion avec MFA

À la première connexion Keycloak, scanne le QR code ou saisis la clé de configuration affichée dans ton application d’authentification. Confirme en saisissant le code à six chiffres qu’elle génère. Lors des connexions suivantes, saisis le code affiché dans l’application. Le parcours s’applique aussi au super admin et aux demandeurs inscrits depuis le client.

### Gérer les comptes

Sur le web, connecte-toi avec `superadmin` et le mot de passe `KEYCLOAK_SUPER_ADMIN_PASSWORD` de ton fichier `.env`. L’écran **Gestion des comptes** affiche les utilisateurs Keycloak avec leur rôle et l’état de leur compte. Tu peux rechercher par nom, identifiant ou courriel, puis ouvrir **Créer un compte** pour ajouter un responsable ou un technicien. Le nouveau membre choisit son mot de passe définitif à sa première connexion.

L’onglet **Interventions** permet au super admin de consulter toutes les demandes, leur détail, leurs commentaires et leur historique en lecture seule, en liste ou en Kanban.

Sur Angular ou Ionic, **Créer un compte demandeur** ouvre l’inscription Keycloak. Le compte reçoit automatiquement le rôle `REQUESTER`. Les responsables voient les techniciens actifs du même realm au moment d’affecter une intervention.

`infra/local.py prepare` génère les deux secrets locaux s’ils sont absents. Sur un realm existant, suis la procédure de mise à jour des comptes. [Exploitation](docs/EXPLOITATION.md)

## Tester

```sh
./mvnw package
npm test
npm run build
npx playwright install chromium
python3 infra/test-local.py
```

Le lanceur utilise un projet Docker et des volumes dédiés pour les comptes, interventions et notifications des tests HTTP et navigateur. Il vérifie deux démarrages de Keycloak puis supprime les volumes de test en fin d’exécution.

## Observabilité

Le profil Compose `monitoring` collecte les métriques des quatre services JVM. Après le démarrage décrit dans le [guide d’exploitation](docs/EXPLOITATION.md), ouvre [Prometheus](http://127.0.0.1:9090/targets) et [Grafana](http://127.0.0.1:3000).

## Mobile natif

```sh
npm run build:mobile
npx cap sync
npx cap open android
npx cap open ios
```

Android utilise le SDK local et le JDK 21 pour le build Capacitor. Sur l’émulateur Android, redirige les ports avec `adb reverse tcp:8080 tcp:8080`, `adb reverse tcp:8081 tcp:8081` et `adb reverse tcp:8180 tcp:8180`. Le simulateur iOS peut se connecter directement au Mac. Le flux d’authentification ouvre le navigateur et revient par `cm.brightky.serviceops://oidc`. Pour un appareil physique, utilise les adresses du réseau de test et configure l’issuer et les origines correspondantes. [Documentation Capacitor](https://capacitorjs.com/docs/getting-started/environment-setup)

## Références du projet

- [Contrat HTTP](contracts/openapi.yaml) et [événement d’affectation](contracts/events/request.assigned.v1.schema.json)
- [Architecture et décisions](docs/ARCHITECTURE.md)
- [Livraison et diagnostic](docs/EXPLOITATION.md)
- [Bilan de validation](docs/VALIDATION.md)
- [Progression](PROGRESSION.md) et [programme complet](PROGRAMME.md)
