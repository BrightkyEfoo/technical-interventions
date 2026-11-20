# Bilan de validation · 20 novembre 2026

## Parcours vérifiés

| Jalon | Preuve locale |
| --- | --- |
| J1 à J3 · Domaine, REST, droits et PostgreSQL | 49 tests Java et 3 tests Kotlin passent. Le scénario HTTP vérifie les migrations, les rôles, les conflits, la pagination filtrée et le cycle jusqu’à la clôture. Deux affectations concurrentes depuis la même version donnent un succès et un conflit. |
| J4 et J5 · Angular et RxJS | Tests navigateur sur Java et Kotlin et test marble de la recherche. Les changements de colonne suivent la réponse de l’API. |
| J6 et J11 · Ionic | Les deux parcours navigateur couvrent le brouillon hors ligne, le conflit sans perte du texte, la confirmation et la relance après perte de réponse. Sur Android, le scénario en mode avion a rouvert un brouillon après arrêt du processus et l’a envoyé à Java puis à Kotlin. L’API valide des images PNG et refuse un fichier invalide. |
| J7 et J8 · Kotlin | La suite HTTP commune et les tests navigateur s’exécutent sur la seconde variante et une base distincte. |
| J9 et J10 · Notifications | Java et Kotlin passent le test sur RabbitMQ réel avec interruption, reprise, redélivrance du même événement puis injection d’un message invalide dans la file de diagnostic. |
| J11 · Concurrence | Java et Kotlin vérifient indépendamment la propagation d’erreur, le délai commun et l’annulation de la tâche sœur. |
| J12 · Livraison | Quatre JAR, deux builds client et six images Docker locales. Les images finales ont répondu à Actuator et passé la suite commune sur les ports de validation. Une demande existante est relue pour chaque variante après redémarrage. |
| J13 · Tests et mesure | Deux défauts ont été injectés dans des copies temporaires du code de reprise mobile et détectés par le test. Les mesures de latence locales sont consignées avec le protocole. |

Les versions Android et iOS d’Ionic ont été compilées et lancées. Android a servi au test hors ligne sur les deux variantes, avec le profil de démonstration. iOS a été construit, installé et démarré sur un simulateur iPhone 16 Pro. L’authentification OIDC a été vérifiée par les parcours navigateur contre les deux backends.

La capture native Android a été exécutée sur l’émulateur Pixel 9 contre Java et Kotlin. Pour chaque variante, la photo de la scène virtuelle a été sélectionnée dans l’application caméra, envoyée depuis Ionic et confirmée dans l’historique de la demande par une entrée `ATTACH`.

La CI distante et le déploiement public restent à exécuter dans les environnements cibles. Les étapes de build, test, démarrage et vérification ont été jouées localement.

## Gestion des comptes

La suite HTTP [accounts.py](../tests/acceptance/accounts.py) passe contre Java et Kotlin avec Keycloak. Elle vérifie les refus sans jeton et pour les trois rôles opérationnels, le rejet d’un rôle ou d’un mot de passe invalide, les doublons de courriel et d’identifiant, puis l’apparition d’un nouveau technicien dans l’annuaire et son affectation à une demande.

Quatre parcours [Playwright](../tests/e2e/accounts.spec.ts) couvrent l’inscription depuis Angular et Ionic web, l’attribution exclusive du rôle métier `REQUESTER` malgré un rôle injecté dans le formulaire, la création de comptes depuis l’écran super admin et le changement obligatoire du mot de passe au premier accès. Les quatre parcours existants du Kanban et du mode hors ligne passent aussi. Les paquets Android et iOS ont été reconstruits. Les nouveaux parcours d’authentification ont été exécutés dans Chromium.

L’annuaire a été vérifié sur les deux API avec recherche par identifiant ou courriel, pagination sans doublon, rôles réels et refus d’accès pour les autres comptes. Les tests Chromium couvrent l’apparition des comptes dans les résultats, la fermeture du formulaire au clavier et le glisser-déposer du Kanban, y compris en cas de conflit. Le formulaire et le tableau ont aussi été inspectés à 390 et 1 440 pixels de large.

Les deux API laissent le super admin lire les demandes, leur historique et leurs commentaires. Les tests HTTP vérifient le rejet de la création, de l’affectation et de l’ajout de commentaires pour ce rôle. Deux créations concurrentes avec le même courriel, dans des casses différentes, donnent un seul compte et une réponse `409`. Le [lanceur isolé](../infra/test-local.py) confirme les cinq UUID du seed avant et après le redémarrage de Keycloak.

## Mesure comparative

La [mesure brute](evidence/latency-local.json) utilise une recherche authentifiée sans résultat. Chaque variante a reçu 12 appels de chauffe puis 60 appels séquentiels sur le même Mac ARM64. Le test mesure le trajet HTTP local jusqu’à la réponse PostgreSQL. Il a été exécuté pendant des compilations locales et sans contrôle de l’ordre des variantes. Les valeurs sont descriptives de cette exécution. Une attribution à Java ou Kotlin demanderait des répétitions alternées, des jeux de données équivalents et une machine au repos.

## Tests de régression et mutations

La suite existante couvrait 45 tests Java autour du domaine, des cas d’usage et de Spring MVC. La suite étendue a ajouté une preuve indépendante du parcours complet et des cas sans équivalent dans le socle, notamment deux auteurs sur la même version, la panne RabbitMQ, la réponse réseau perdue et le redémarrage hors ligne. Un écart dans les fixtures de rôles et un écart de résolution de `localhost` ont été trouvés lors des premières exécutions. La correction conservait les assertions et vérifiait la cause avant rejeu.

`node tests/client/mutations.mjs` démontre deux défauts précis. Le premier bloquait la relance alors que le serveur avait déjà enregistré la commande. Le second effaçait le texte lors du choix d’une nouvelle version. Chaque mutation a provoqué une erreur d’assertion attendue.

## Trame de restitution

1. Ouvrir Angular et créer une demande. Lire l’UUID, le statut et la version dans Swagger.
2. Se connecter en responsable. Affecter depuis le Kanban, puis ouvrir la carte et son historique.
3. Sur Ionic, ouvrir l’intervention en technicien. Commencer le travail, couper le réseau et enregistrer un brouillon.
4. Relancer Ionic, retrouver le texte, rétablir le réseau et confirmer. Présenter le scénario de perte de réponse et la clé d’idempotence.
5. Comparer les résultats de Java et Kotlin. Expliquer le rôle de JPA, de Flyway, du verrouillage optimiste et de l’outbox.
6. Montrer la procédure de panne RabbitMQ, le rattrapage et la file de diagnostic.

## Validation après ajout de la MFA

Le realm existant a conservé ses comptes. Un realm neuf a confirmé que la saisie du mot de passe ne suffit pas pour obtenir le jeton et a refusé un code incorrect. Le jeton a été délivré après enrôlement TOTP. Le client de connexion directe est désactivé dans le realm local.

Les deux variantes passent les suites HTTP sur un projet Docker jetable. Le seed contient cinq comptes avant et après redémarrage. La vérification des notifications a interrompu RabbitMQ et confirmé la reprise et la déduplication. Les huit parcours Playwright passent avec le flux MFA, y compris l’inscription, la gestion des comptes, le Kanban et le brouillon Ionic.

Les quatre cibles Prometheus sont actives. Le dashboard Grafana ServiceOps a été lu via son API locale et expose quatre graphiques.
