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
    private static final long serialVersionUID = 6L;
    private static final Logger logger = LoggerFactory.getLogger(AdminTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class TiendaDTO {
        public int codigo, huecosTurno1, huecosTurno2, huecosTurno3, huecosTurno4;
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
                case "save":
                    handleSave(request, spConn);
                    break;
                case "syncAll":
                    break;
                default:
                    throw new ServletException("Acción desconocida: " + action);
            }
            syncFromSPtoDB(adminUser);
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Operación completada y sistema sincronizado.");
        } catch (Exception e) {
            LogUtil.logException(logger, e, "Error en doPost de AdminTiendasServlet", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error en la operación. El problema ha sido registrado.");
        }
    }

    private void handleSave(HttpServletRequest request, Connection conn) throws Exception {
        int codigo = Integer.parseInt(request.getParameter("codigo"));

        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("disponible", "S".equals(request.getParameter("disponible")));
        fields.getAdditionalData().put("HuecosTurno1", Integer.parseInt(request.getParameter("huecosTurno1")));
        fields.getAdditionalData().put("HuecosTurno2", Integer.parseInt(request.getParameter("huecosTurno2")));
        fields.getAdditionalData().put("HuecosTurno3", Integer.parseInt(request.getParameter("huecosTurno3")));
        fields.getAdditionalData().put("HuecosTurno4", Integer.parseInt(request.getParameter("huecosTurno4")));

        String listId = getListId();
        String itemId = findItemIdByCodigo(conn, listId, codigo);
        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
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
                Integer codigo = parseToInt(fields.get("codigo"));
                if (codigo == null) continue;
                spCodes.add(codigo);

                stmt.setInt(1, codigo);
                stmt.setString(2, getString(fields, "denominacion"));
                stmt.setString(3, getString(fields, "direccion"));
                stmt.setString(4, getString(fields, "cp"));
                stmt.setString(5, getString(fields, "poblacion"));
                stmt.setString(6, getString(fields, "cadena"));
                stmt.setString(7, Boolean.TRUE.equals(fields.get("disponible")) ? "S" : "N");
                stmt.setString(8, getString(fields, "Lat").replace(',', '.'));
                stmt.setString(9, getString(fields, "Lon").replace(',', '.'));
                stmt.setString(10, String.format("%04d", parseToInt(fields.get("prioridad"))));
                stmt.setInt(11, parseToInt(fields.get("HuecosTurno1")));
                stmt.setInt(12, parseToInt(fields.get("HuecosTurno2")));
                stmt.setInt(13, parseToInt(fields.get("HuecosTurno3")));
                stmt.setInt(14, parseToInt(fields.get("HuecosTurno4")));

                String sqlRowUUID = findExistingUuid(conn, codigo);
                stmt.setString(15, (sqlRowUUID != null) ? sqlRowUUID : UUID.randomUUID().toString());

                stmt.setString(16, getString(fields, "SUPERVISOR"));
                stmt.setString(17, getString(fields, "Coordinador"));
                stmt.setString(18, getString(fields, "Marca"));
                stmt.setString(19, getString(fields, "CodZona"));
                stmt.setString(20, getString(fields, "Zona"));
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

    private String findItemIdByCodigo(Connection conn, String listId, int codigo) throws Exception {
        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "codigo", String.valueOf(codigo));
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
        List<TiendaDTO> tiendas = new ArrayList<>();
        String sql = "SELECT * FROM tiendas ORDER BY denominacion";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) tiendas.add(mapRowToTienda(rs));
            mapper.writeValue(response.getWriter(), tiendas);
        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error en GET de tiendas", getUsuario(request));
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos.");
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
