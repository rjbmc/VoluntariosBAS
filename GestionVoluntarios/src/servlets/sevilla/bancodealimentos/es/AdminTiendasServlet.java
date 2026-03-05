package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 8L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(AdminTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class TiendaDTO {
        public int codigo; // Se mantiene como int en BD local
        public int huecosTurno1, huecosTurno2, huecosTurno3, huecosTurno4;
        public String denominacion, direccion, lat, lon, cp, poblacion, cadena, disponible, prioridad, sqlRowUUID, supervisor, coordinador, marca, codZona, zona, modalidad;
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            return false;
        }
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) {
            return (Boolean) isAdminAttr;
        }
        if (isAdminAttr instanceof String) {
            return "S".equalsIgnoreCase((String) isAdminAttr);
        }
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleGetRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        String adminUser = getUsuario(request);
        String action = request.getParameter("action");
        String context = String.format("Admin: %s, Action: %s", adminUser, action);

        try (Connection spConn = DatabaseUtil.getConnection()) {
            switch (action) {
                case "refreshAll":
                    syncFromSPtoDB(adminUser);
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Sincronización completada con éxito.");
                    break;

                case "refresh":
                    String codigo = request.getParameter("codigo");
                    refreshSingleTienda(adminUser, codigo);
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Tienda " + codigo + " actualizada correctamente.");
                    break;

                case "save":
                    handleSave(request, spConn);
                    syncFromSPtoDB(adminUser);
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Guardado y sincronizado correctamente.");
                    break;

                default:
                    sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Acción desconocida: " + action);
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            String stackTrace = getStackTraceAsString(e);

            logger.error("Error en doPost de AdminTiendasServlet. Contexto: {}", context, e);

            try (Connection conn = DatabaseUtil.getConnection()) {
                LogUtil.logOperation(conn, "ERROR_SYNC", adminUser,
                        "Error en sincronización: " + errorMsg + " - " + stackTrace.substring(0, Math.min(500, stackTrace.length())));
            } catch (SQLException ex) {
                logger.error("No se pudo registrar el error en la tabla de logs", ex);
            }

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error en la sincronización: " + errorMsg);
            errorResponse.put("detail", stackTrace);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            mapper.writeValue(response.getWriter(), errorResponse);
        }
    }

    private String getStackTraceAsString(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private void refreshSingleTienda(String adminUser, String codigo) throws Exception {
        String listId = getListId();

        String itemId = SharePointUtil.findItemIdByFieldValue(null, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "codigo", codigo);

        if (itemId == null) {
            throw new Exception("La tienda con código " + codigo + " no existe en SharePoint");
        }

        ListItemCollectionResponse spTiendasResponse = SharePointUtil.getListItems(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId);
        ListItem tiendaSP = null;

        if (spTiendasResponse != null && spTiendasResponse.getValue() != null) {
            for (ListItem item : spTiendasResponse.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                String spCodigo = getString(fields, "codigo");
                if (spCodigo != null && spCodigo.equals(codigo)) {
                    tiendaSP = item;
                    break;
                }
            }
        }

        if (tiendaSP == null) {
            throw new Exception("No se encontraron datos para la tienda " + codigo + " en SharePoint");
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<ListItem> items = new ArrayList<>();
                items.add(tiendaSP);
                Set<Integer> spCodes = upsertTiendas(conn, items);
                conn.commit();

                LogUtil.logOperation(conn, "REFRESH_TIENDA", adminUser,
                        "Tienda " + codigo + " actualizada desde SharePoint");

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private void handleSave(HttpServletRequest request, Connection conn) throws Exception {
        try {
            String codigo = request.getParameter("codigo"); // Ahora es String

            FieldValueSet fields = new FieldValueSet();
            fields.getAdditionalData().put("codigo", codigo); // Enviar como string
            fields.getAdditionalData().put("disponible", "S".equals(request.getParameter("disponible")));
            fields.getAdditionalData().put("huecosTurno1", Integer.parseInt(request.getParameter("huecosTurno1")));
            fields.getAdditionalData().put("huecosTurno2", Integer.parseInt(request.getParameter("huecosTurno2")));
            fields.getAdditionalData().put("huecosTurno3", Integer.parseInt(request.getParameter("huecosTurno3")));
            fields.getAdditionalData().put("huecosTurno4", Integer.parseInt(request.getParameter("huecosTurno4")));

            String listId = getListId();
            String itemId = findItemIdByCodigo(conn, listId, codigo);
            SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);

            logger.info("Tienda {} actualizada correctamente en SharePoint", codigo);

        } catch (Exception e) {
            logger.error("Error al guardar tienda", e);
            throw new Exception("Error al guardar: " + e.getMessage());
        }
    }

    private void syncFromSPtoDB(String adminUser) throws Exception {
        String listId = getListId();
        ListItemCollectionResponse spTiendasResponse = SharePointUtil.getListItems(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<ListItem> spItems = (spTiendasResponse != null && spTiendasResponse.getValue() != null) ? spTiendasResponse.getValue() : new ArrayList<>();
                Set<Integer> spCodes = upsertTiendas(conn, spItems);
                deactivateOrphanedTiendas(conn, spCodes);
                conn.commit();
                LogUtil.logOperation(conn, "SYNC_TIENDAS_SP_DB", adminUser, "Sincronización completa SP -> DB de tiendas realizada.");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private Set<Integer> upsertTiendas(Connection conn, List<ListItem> spItems) throws SQLException {
        Set<Integer> spCodes = new HashSet<>();
        String upsertSql = "INSERT INTO tiendas (codigo, denominacion, Direccion, cp, Poblacion, Cadena, disponible, Lat, Lon, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, SqlRowUUID, Supervisor, Coordinador, Marca, CodZona, Zona, Modalidad, notificar) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S') "
                + "ON DUPLICATE KEY UPDATE denominacion=VALUES(denominacion), Direccion=VALUES(Direccion), cp=VALUES(cp), Poblacion=VALUES(Poblacion), Cadena=VALUES(Cadena), "
                + "disponible=VALUES(disponible), Lat=VALUES(Lat), Lon=VALUES(Lon), prioridad=VALUES(prioridad), HuecosTurno1=VALUES(HuecosTurno1), HuecosTurno2=VALUES(HuecosTurno2), "
                + "HuecosTurno3=VALUES(HuecosTurno3), HuecosTurno4=VALUES(HuecosTurno4), Supervisor=VALUES(Supervisor), Coordinador=VALUES(Coordinador), Marca=VALUES(Marca), "
                + "CodZona=VALUES(CodZona), Zona=VALUES(Zona), Modalidad=VALUES(Modalidad), notificar='S'";

        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            for (ListItem item : spItems) {
                Map<String, Object> fields = item.getFields().getAdditionalData();

                logger.debug("Procesando tienda desde SharePoint");

                // Leer el código como String desde SharePoint
                String codigoStr = getString(fields, "codigo");
                if (codigoStr == null || codigoStr.isEmpty()) {
                    logger.warn("Item sin código, se omite.");
                    continue;
                }
                
                // Convertir a entero para la BD local
                Integer codigo = Integer.parseInt(codigoStr);
                spCodes.add(codigo);

                stmt.setInt(1, codigo);
                stmt.setString(2, getString(fields, "denominacion"));
                stmt.setString(3, getString(fields, "direccion"));
                stmt.setString(4, getString(fields, "cp"));
                stmt.setString(5, getString(fields, "poblacion"));
                stmt.setString(6, getString(fields, "cadena"));
                stmt.setString(7, Boolean.TRUE.equals(fields.get("disponible")) ? "S" : "N");

                // --- LATITUD (campo 8) - Campo en minúsculas 'lat' ---
                String latStr = getString(fields, "lat").replace(',', '.');
                if (latStr == null || latStr.trim().isEmpty()) {
                    logger.debug("No se encontró latitud para tienda {}, usando 0.0", codigo);
                    stmt.setString(8, "0.0");
                } else {
                    stmt.setString(8, latStr);
                }

                // --- LONGITUD (campo 9) - Campo en minúsculas 'lon' ---
                String lonStr = getString(fields, "lon").replace(',', '.');
                if (lonStr == null || lonStr.trim().isEmpty()) {
                    logger.debug("No se encontró longitud para tienda {}, usando 0.0", codigo);
                    stmt.setString(9, "0.0");
                } else {
                    stmt.setString(9, lonStr);
                }

                stmt.setString(10, String.format("%04d", parseToInt(fields.get("prioridad"))));

                stmt.setInt(11, parseToInt(fields.get("huecosTurno1")));
                stmt.setInt(12, parseToInt(fields.get("huecosTurno2")));
                stmt.setInt(13, parseToInt(fields.get("huecosTurno3")));
                stmt.setInt(14, parseToInt(fields.get("huecosTurno4")));

                String sqlRowUUID = findExistingUuid(conn, codigo);
                stmt.setString(15, (sqlRowUUID != null) ? sqlRowUUID : UUID.randomUUID().toString());

                stmt.setString(16, getString(fields, "SUPERVISOR"));
                stmt.setString(17, getString(fields, "Coordinador"));
                stmt.setString(18, getString(fields, "Marca"));
                stmt.setString(19, getString(fields, "CodZona"));

                // --- ZONA (campo 20) - Se mantiene en mayúsculas como estaba ---
                String zona = getString(fields, "ZONA");
                stmt.setString(20, zona);

                stmt.setString(21, getString(fields, "Modalidad"));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        return spCodes;
    }

    private void deactivateOrphanedTiendas(Connection conn, Set<Integer> spCodes) throws SQLException {
        if (spCodes.isEmpty()) {
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE tiendas SET disponible = 'N', notificar = 'S' WHERE disponible = 'S'")) {
                stmt.executeUpdate();
            }
            return;
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(spCodes.size(), "?"));
        String sql = "UPDATE tiendas SET disponible = 'N', notificar = 'S' WHERE disponible = 'S' AND codigo NOT IN (" + placeholders + ")";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            for (Integer code : spCodes) {
                stmt.setInt(i++, code);
            }
            stmt.executeUpdate();
        }
    }

    private String findExistingUuid(Connection conn, int codigo) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT SqlRowUUID FROM tiendas WHERE codigo = ?")) {
            stmt.setInt(1, codigo);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String getListId() throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, SharePointUtil.LIST_NAME_TIENDAS);
        if (listId == null) throw new IOException("La lista de tiendas no fue encontrada en SharePoint.");
        return listId;
    }

    private String findItemIdByCodigo(Connection conn, String listId, String codigo) throws Exception {
        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "codigo", codigo);
        if (itemId == null) throw new IOException("La tienda con código '" + codigo + "' no fue encontrada en SharePoint.");
        return itemId;
    }

    private void handleGetRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        String supervisor = request.getParameter("supervisor");
        String zona = request.getParameter("zona");

        List<TiendaDTO> tiendas = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT * FROM tiendas WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (supervisor != null && !supervisor.trim().isEmpty()) {
            sql.append(" AND Supervisor = ?");
            params.add(supervisor);
        }

        if (zona != null && !zona.trim().isEmpty()) {
            sql.append(" AND Zona = ?");
            params.add(zona);
        }

        sql.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setString(i + 1, (String) params.get(i));
            }

            logger.info("Ejecutando consulta de tiendas con filtros - Supervisor: {}, Zona: {}", supervisor, zona);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tiendas.add(mapRowToTienda(rs));
                }
            }

            logger.info("Se encontraron {} tiendas", tiendas.size());
            mapper.writeValue(response.getWriter(), tiendas);

        } catch (SQLException e) {
            logger.error("Error SQL en GET de tiendas con filtros. Supervisor: {}, Zona: {}", supervisor, zona, e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error de base de datos: " + e.getMessage());
        }
    }

    private TiendaDTO mapRowToTienda(ResultSet rs) throws SQLException {
        TiendaDTO t = new TiendaDTO();
        t.codigo = rs.getInt("codigo");
        t.denominacion = rs.getString("denominacion");
        t.direccion = rs.getString("Direccion");
        t.lat = rs.getString("Lat");
        t.lon = rs.getString("Lon");
        t.cp = rs.getString("cp");
        t.poblacion = rs.getString("Poblacion");
        t.cadena = rs.getString("Cadena");
        t.disponible = rs.getString("disponible");
        t.prioridad = rs.getString("prioridad");
        t.huecosTurno1 = rs.getInt("HuecosTurno1");
        t.huecosTurno2 = rs.getInt("HuecosTurno2");
        t.huecosTurno3 = rs.getInt("HuecosTurno3");
        t.huecosTurno4 = rs.getInt("HuecosTurno4");
        t.sqlRowUUID = rs.getString("SqlRowUUID");
        t.supervisor = rs.getString("Supervisor");
        t.coordinador = rs.getString("Coordinador");
        t.marca = rs.getString("Marca");
        t.codZona = rs.getString("CodZona");
        t.zona = rs.getString("Zona");
        t.modalidad = rs.getString("Modalidad");
        return t;
    }

    private String getUsuario(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return (session != null) ? (String) session.getAttribute("usuario") : "Anónimo";
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            mapper.writeValue(response.getWriter(), res);
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    private Integer parseToInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            String str = String.valueOf(obj).trim();
            if (str.contains(".")) {
                return (int) Double.parseDouble(str);
            }
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}