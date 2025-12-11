package util.sevilla.bancodealimentos.es;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;

/**
 * Clase de utilidad unificada para la integración con Microsoft SharePoint.
 * Gestiona tanto la manipulación de Listas (Campañas, Voluntarios) como la subida de Archivos.
 * Utiliza Microsoft Graph SDK v6 y SLF4J para el registro de eventos.
 */
public class SharepointUtil {

    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(SharepointUtil.class);

    // --- CREDENCIALES ---
    private static final String CLIENT_ID = "e15a2c05-a43a-487f-a78b-aa7fade86e7a";
    private static final String TENANT_ID = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec";
    private static final String CLIENT_SECRET = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd";

    // --- IDs DE SITIO ---
    public static final String SITE_ID = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdsevilla.sharepoint.com,98b8faf3-b1b7-4a7b-8cd8-744fbe8c8c79,b2a4e075-4f56-47fe-9fc5-09466a41a27a";

    private static GraphServiceClient graphClient = null;

    /**
     * Inicializa el cliente de Microsoft Graph (Singleton).
     */
    private static synchronized void initializeGraphClient() {
        if (graphClient == null) {
            try {
                ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                    .clientId(CLIENT_ID)
                    .tenantId(TENANT_ID)
                    .clientSecret(CLIENT_SECRET)
                    .build();

                // Alcance por defecto para Graph
                String[] scopes = new String[] { "https://graph.microsoft.com/.default" };
                graphClient = new GraphServiceClient(credential, scopes);
                // logger.info("Cliente de Microsoft Graph inicializado.");
            } catch (Exception e) {
                logger.error("Error crítico al inicializar Graph Client", e);
                throw new RuntimeException("Fallo en la conexión a Microsoft Graph", e);
            }
        }
    }

    // ==========================================
    //       MÉTODOS DE GESTIÓN DE LISTAS
    // ==========================================

    public static ListCollectionResponse getAllLists(String siteId) throws Exception {
        initializeGraphClient();
        try {
            return graphClient.sites().bySiteId(siteId).lists().get(requestConfiguration -> {
                requestConfiguration.queryParameters.select = new String[]{"id", "displayName", "webUrl"};
            });
        } catch (Exception e) {
            logger.error("Error al obtener todas las listas del sitio {}", siteId, e);
            throw e;
        }
    }

    public static String getListId(String targetSiteId, String listName) throws Exception {
        initializeGraphClient();
        try {
            ListCollectionResponse lists = graphClient.sites().bySiteId(targetSiteId).lists().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = "displayName eq '" + listName + "'";
            });
            if (lists != null && lists.getValue() != null && !lists.getValue().isEmpty()) {
                return lists.getValue().get(0).getId();
            }
            logger.warn("Lista '{}' no encontrada en el sitio {}", listName, targetSiteId);
            return null;
        } catch (Exception e) {
            logger.error("Error obteniendo ID de lista '{}' en sitio '{}'", listName, targetSiteId, e);
            throw e;
        }
    }

    public static ColumnDefinitionCollectionResponse getListColumns(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        try {
            return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).columns().get();
        } catch (Exception e) {
            logger.error("Error obteniendo columnas de lista {}", listId, e);
            throw e;
        }
    }

    public static ListItemCollectionResponse getListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        try {
            return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.expand = new String[]{"fields"};
            });
        } catch (Exception e) {
            logger.error("Error obteniendo items de lista {}", listId, e);
            throw e;
        }
    }

    public static ListItem createListItem(String targetSiteId, String listId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        ListItem newItem = new ListItem();
        newItem.setFields(fields);
        try {
            return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().post(newItem);
        } catch (Exception e) {
            logger.error("Error creando ítem en lista {} del sitio {}", listId, targetSiteId, e);
            throw e;
        }
    }

    public static FieldValueSet updateListItem(String targetSiteId, String listId, String itemId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        try {
            return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).fields().patch(fields);
        } catch (Exception e) {
            logger.error("Error actualizando ítem {} en lista {}", itemId, listId, e);
            throw e;
        }
    }

    public static void deleteListItem(String targetSiteId, String listId, String itemId) throws Exception {
        initializeGraphClient();
        try {
            graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).delete();
        } catch (Exception e) {
            logger.error("Error eliminando ítem {} de lista {}", itemId, listId, e);
            throw e;
        }
    }
    
    public static void deleteAllListItems(String targetSiteId, String listId) throws Exception {
         initializeGraphClient();
         logger.info("Iniciando borrado masivo de ítems en lista: {}", listId);
         try {
             ListItemCollectionResponse items = getListItems(targetSiteId, listId);
             if (items != null && items.getValue() != null) {
                 for (ListItem item : items.getValue()) {
                     deleteListItem(targetSiteId, listId, item.getId());
                 }
             }
             logger.info("Borrado masivo completado.");
         } catch (Exception e) {
             logger.error("Error en borrado masivo de lista {}", listId, e);
             throw e;
         }
    }

    public static String findItemIdByFieldValue(String siteId, String listId, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        try {
            ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = "fields/" + fieldName + " eq '" + fieldValue + "'";
                requestConfiguration.queryParameters.select = new String[]{"id"};
                requestConfiguration.queryParameters.top = 1;
                requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });

            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                return response.getValue().get(0).getId();
            }
            return null;
        } catch (Exception e) {
            logger.error("Error buscando item por campo '{}' en lista {}", fieldName, listId, e);
            throw e;
        }
    }

    // ==========================================
    //       MÉTODOS DE SUBIDA DE ARCHIVOS
    // ==========================================

    /**
     * Sube un archivo a la biblioteca de documentos (Drive) por defecto del sitio.
     */
    public static boolean subirArchivo(String siteId, String nombreArchivo, byte[] datos) {
        initializeGraphClient();
        logger.info("Iniciando subida de archivo '{}' al sitio: {}", nombreArchivo, siteId);

        if (nombreArchivo == null || nombreArchivo.isEmpty() || datos == null || datos.length == 0) {
            logger.error("Datos inválidos para la subida: nombre o contenido vacío.");
            return false;
        }

        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(datos);
            
            // 1. Obtener ID del Drive por defecto del sitio
            Drive defaultDrive = graphClient.sites().bySiteId(siteId).drive().get();
            String driveId = defaultDrive.getId();

            // 2. Usar la colección de drives global para acceder al ítem por su ruta relativa.
            // Sintaxis: "root:/nombreArchivo:" indica que accedemos al archivo en la raíz por su nombre.
            // Esta es la forma estándar de la API REST para evitar depender de métodos helper como itemWithPath.
            String itemPathId = "root:/" + nombreArchivo + ":";

            graphClient.drives().byDriveId(driveId)
                .items().byDriveItemId(itemPathId)
                .content()
                .put(inputStream);

            logger.info("Archivo '{}' subido correctamente.", nombreArchivo);
            return true;

        } catch (Exception e) {
            logger.error("Error al subir el archivo '{}' a SharePoint.", nombreArchivo, e);
            return false;
        }
    }
    
    /**
     * Sobrecarga para usar el SITE_ID por defecto si no se especifica otro.
     */
    public static boolean subirArchivo(String nombreArchivo, byte[] datos) {
        return subirArchivo(SITE_ID, nombreArchivo, datos);
    }

    // ==========================================
    //       MÉTODOS DE LOGGING (ADAPTADOS)
    // ==========================================

    /**
     * Método de logging legacy adaptado.
     * Redirige al logger SLF4J en lugar de escribir en un archivo manual.
     */
    public static void logError(String listName, String uuid, String message) {
        logger.error("Error de replicación en lista '{}' para UUID '{}': {}", listName, uuid, message);
    }
    
    public static void verificarConexion() {
        try {
            initializeGraphClient();
            // Intentamos obtener información básica del sitio
            graphClient.sites().bySiteId(SITE_ID).get();
            logger.info("Conexión con SharePoint verificada correctamente.");
        } catch (Exception e) {
            logger.error("Fallo al verificar la conexión con SharePoint.", e);
        }
    }
}