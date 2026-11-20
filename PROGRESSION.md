# ServiceOps Lab — Progression

**20 novembre 2026 · Livraison locale ServiceOps Lab**

Les livrables exécutables se trouvent dans le [README](README.md). Le [bilan de validation](docs/VALIDATION.md) indique les preuves obtenues contre les deux backends. Les étapes ci-dessous retracent le socle Java de la première séance.

## Socle Java · première séance

| Étape | Résultat | Preuve |
| --- | --- | --- |
| Lecture préparatoire | Classes et constructeurs Java, puis `assertThrows` | Lecture terminée, confirmée par l’apprenant |
| Initialisation | Spring Initializr, Maven, Spring Boot 4.1.2 et OpenJDK 27. Le module se trouve dans `backends/java/serviceops-java` | [Configuration Maven](backends/java/serviceops-java/pom.xml) et SDK IntelliJ vérifiés |
| Démarrage Spring | `contextLoads()` est passé dans IntelliJ puis lors de la revue | Résultat communiqué par l’apprenant, confirmé avec le lanceur JUnit |
| Création d’une demande (R02) | Champs privés et accès en lecture via `title()` et `status()`. Le premier test a échoué sur le statut `null`, puis est passé après l’initialisation à `OPEN` | [Request.java](backends/java/serviceops-java/src/main/java/cm/brightky/serviceopsjava/requests/domain/Request.java) et historique de la séance |
| Validation du titre (R01) | `strip()` est appliqué avant le contrôle de longueur. Les tests couvrent `"  LED  "`, les refus à 4 et 121, et les acceptations à 5 et 120 avec des espaces ajoutés autour du titre | [RequestTest.java](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/domain/RequestTest.java) |
| Affectation (R03) | `Request` vérifie le rôle du responsable et du destinataire et garde l’UUID du technicien. La réaffectation est autorisée avant démarrage | 5 nouveaux cas dans `RequestTest` couvrent l’affectation, la réaffectation et les refus sans modification de la demande |
| Démarrage (R05) | Le technicien affecté peut passer de `ASSIGNED` à `IN_PROGRESS`. Les tests couvrent la comparaison de l’UUID par valeur, les rôles, le statut et le refus de réaffecter après démarrage | 6 nouveaux cas dans [RequestTest.java](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/domain/RequestTest.java) |
| Exécution du module Java | 18 cas du domaine, 2 du dépôt, 2 de création, 4 d’affectation, 17 des contrôleurs, 1 du gestionnaire et `contextLoads()` réussis sur OpenJDK 27 | 45 réussites au total avec le lanceur JUnit après compilation Maven |
| Identité d’une demande | `Request` conserve un identifiant `final` de type `RequestId`. La demande refuse un identifiant absent et `RequestId` refuse un UUID `null` | [RequestId.java](backends/java/serviceops-java/src/main/java/cm/brightky/serviceopsjava/requests/domain/RequestId.java) et 18 cas de `RequestTest` relancés avec succès |
| Dépôt en mémoire | `RequestRepository` définit `save` et `findById`. Son adaptateur renvoie la demande enregistrée, la retrouve avec un autre `RequestId` de même valeur et renvoie un `Optional` vide pour un identifiant inconnu | [Deux tests JUnit](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/infrastructure/persistence/InMemoryRequestRepositoryTest.java) |
| Cas d’usage de création | `CreateRequest` génère l’UUID, construit la demande et renvoie le résultat de `save`. Une demande valide est relue avec son titre normalisé et le statut `OPEN`. Un titre invalide est refusé avant tout appel au dépôt | [CreateRequestTest.java](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/application/CreateRequestTest.java) |
| Cas d’usage d’affectation | `AssignRequest` reçoit une `AssignRequestCommand`, charge la demande et les utilisateurs, puis enregistre la demande après `assignTo`. Une recherche vide signale l’entité introuvable avant toute modification | [AssignRequestTest.java](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/application/AssignRequestTest.java) |
| Création par HTTP | `POST /api/v1/requests` renvoie `201` avec l’UUID, le titre normalisé, `OPEN` et `assigneeId` à `null`. Un JSON mal formé reçoit `400` | [Tests MockMvc](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/infrastructure/web/RequestControllerTest.java) |
| Validation HTTP du titre | `@Valid` et `@NotBlank` refusent un titre blanc. Le gestionnaire central traduit les longueurs métier invalides en `400` avec `ProblemDetail` et `INVALID_TITLE` | Tests MockMvc couvrant le titre blanc, `abcd`, `"  LED  "` et 121 caractères |
| Codes d’erreur partagés | `ServiceOpsException` transporte le code métier. Le gestionnaire web le traduit en statut HTTP et `ProblemDetail` | Tests du domaine, du gestionnaire et tests MockMvc |
| Affectation par HTTP | `POST /api/v1/requests/{id}/assign` renvoie `204` sans corps après enregistrement. Une demande inconnue renvoie `404` avec `REQUEST_NOT_FOUND` et une demande démarrée renvoie `409` | [Tests MockMvc d’affectation](backends/java/serviceops-java/src/test/java/cm/brightky/serviceopsjava/requests/infrastructure/web/AssignRequestControllerTest.java) |
| Contrat OpenAPI | springdoc décrit les UUID, les corps et les réponses de création et d’affectation, ainsi que le champ `code` dans les erreurs métier | `/v3/api-docs` vérifié avec MockMvc. [YAML exporté](contracts/openapi.yaml) |
| Première migration PostgreSQL | `V1__create_requests.sql` définit les quatre colonnes d’une demande et les statuts autorisés | SQL exécuté sur une base PostgreSQL 15 vide. Insertion, affectation et refus d’un UUID dupliqué, d’un statut inconnu, d’un titre absent et d’un titre trop long vérifiés |
| PostgreSQL avec Docker Compose | Le service `postgres` démarre avec la configuration locale et un volume nommé | Conteneur `healthy`, montage du volume vérifié et requête SQL authentifiée depuis le Mac sur `127.0.0.1:5433` |

Pendant la séance, nous avons pratiqué les constructeurs, l’encapsulation, les `enum`, `assertEquals`, `assertThrows`, `@ParameterizedTest` et `@ValueSource`. L’exemple `@CsvSource` a servi à expliquer comment fournir deux arguments à un test.

L’accompagnant a appliqué la correction d’affectation à la demande de l’apprenant. L’apprenant a écrit et corrigé `start`. Pendant la revue des tests de démarrage, l’accompagnant a isolé chaque règle dans son propre scénario à la demande de l’apprenant.

## Essayer l’affectation dans IntelliJ

Lance Spring Boot avec l’argument `--spring.profiles.active=demo`. Crée une demande via `POST /api/v1/requests`, puis utilise l’UUID renvoyé par le `201` dans `POST /api/v1/requests/{id}/assign`. Envoie ce corps JSON.

```json
{
  "actorId": "00000000-0000-4000-8000-000000000001",
  "technicianId": "00000000-0000-4000-8000-000000000002"
}
```

Le contrôleur assemble la commande. `AssignRequest` charge la demande et les utilisateurs, demande à `Request` d’appliquer la transition, puis enregistre le résultat. L’`actorId` du corps sert à la démonstration locale. En J3, l’identité de l’acteur viendra de l’authentification OIDC.

## PostgreSQL local avec Docker Compose

Le [fichier Compose](compose.yaml) se trouve à la racine. Il lance PostgreSQL 15 et publie son port sur `127.0.0.1:5433` avec les valeurs de [`.env.example`](.env.example). Compose lit le fichier local `.env`, qui est ignoré par Git. Sur un nouveau poste, copie le modèle vers `.env` et renseigne `POSTGRES_PASSWORD`.

```sh
docker compose config --quiet
docker compose up -d --wait postgres
docker compose ps postgres
```

Pour arrêter PostgreSQL en conservant ses données, utilise `docker compose stop postgres`. Le volume nommé `postgres_data` conserve les fichiers de la base. Les variables `POSTGRES_DB`, `POSTGRES_USER` et `POSTGRES_PASSWORD` servent à initialiser la base et le compte lorsque ce volume est vide.

## Livraison du programme

- Flyway applique les migrations de chaque variante et Hibernate valide le schéma. Les bases Java et Kotlin sont séparées.
- Les deux API couvrent le cycle d’intervention sous Keycloak, le contrôle d’accès par objet, la version optimiste et la reprise idempotente.
- Angular couvre le Kanban et le détail avec l’historique. Ionic couvre la consultation, la photo et le brouillon hors ligne.
- Les deux consommateurs de notifications utilisent leurs bases propres et la reprise RabbitMQ a été vérifiée.
- Les quatre backends JVM et les deux clients sont reproductibles à partir de la racine.

Le [bilan de validation](docs/VALIDATION.md) consigne les tests, les limites et la restitution. Le [guide d’exploitation](docs/EXPLOITATION.md) explique le démarrage des images et le diagnostic.

## Gestion des comptes

- Les deux API vérifient le rôle `SUPER_ADMIN` dans le jeton avant de créer un responsable ou un technicien dans Keycloak. Le membre change son mot de passe à sa première connexion.
- Angular propose l’écran de création des comptes. Angular et Ionic ouvrent le formulaire d’inscription Keycloak, qui attribue le rôle `REQUESTER`.
- Le responsable charge l’annuaire Keycloak à l’ouverture de la boîte d’affectation. Un nouveau technicien actif y figure dès la création du compte.
- Le realm local a été migré vers un volume Docker en conservant les quatre identifiants de comptes existants. Les suites HTTP et navigateur documentent le parcours et les refus d’accès. [Validation](docs/VALIDATION.md)
- L’écran super admin affiche l’annuaire paginé et ouvre la création d’un compte dans un formulaire dédié. Le Kanban présente les interventions sur des cartes compactes et repère le statut dans l’en-tête de chaque colonne.
- Le super admin navigue entre **Comptes** et **Interventions**. Les deux backends autorisent la lecture de toutes les demandes et appliquent séparément les droits de modification.
- La base locale a été réinitialisée après sauvegarde des volumes. Les deux API ont confirmé cinq comptes d’origine et zéro intervention. Les tests utilisent désormais un projet Compose jetable et le démarrage valide l’unicité des identifiants, des courriels et des UUID du seed.
- Les écrans web partagent une échelle d’espacement. Les liens et les titres cliquables des demandes se soulignent au survol. Les panneaux et les formulaires disposent d’une icône de fermeture nommée, et les fenêtres d’affectation et de résolution gèrent Échap et le retour du focus.

## MFA et observabilité

- Keycloak exige maintenant un code TOTP pour chaque rôle. Les comptes ont été conservés et les sessions du realm ont été révoquées lors de la migration.
- L’écran de premier enrôlement affiche ensemble le QR code, la clé de configuration et le champ où saisir le code de vérification.
- Un navigateur Chrome a validé l’enrôlement sur un realm de test neuf. Un code incorrect a été refusé et un code valide a permis d’obtenir un jeton OIDC. Le client de connexion directe est désactivé dans le realm de démonstration.
- Le profil Compose `monitoring` ajoute la collecte des quatre applications JVM et un tableau de bord préconfiguré. [Guide d’exploitation](docs/EXPLOITATION.md)
