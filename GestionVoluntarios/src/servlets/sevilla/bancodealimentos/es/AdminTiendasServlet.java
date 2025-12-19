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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Servlet para la gestión administrativa de tiendas.
 * Maneja el listado, guardado, refresco individual y sincronización global con SharePoint.
 */
@WebServlet("/admin-tiendas")
public class AdminTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(AdminTiendasServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // Usuario con permisos especiales (SuperAdmin)
    private static final String SUPER_USER_ID = "28654139A";

    public static class Tienda {
        public int codigo;
        public String denominacion;
        public String direccion;
        public String lat;
        public String lon;
        public String cp;
        public String poblacion;
        public String cadena;
        public String disponible;
        public int prioridad;
        public int huecosTurno1;
        public int huecosTurno2;
        public int huecosTurno3;
        public int huecosTurno4;
        public String sqlRowUUID; 
        public String supervisor; // AÑADIDO: Campo para el nombre del supervisor
    }

    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || "S".equals(isAdminAttr);
    }

    /**
     * Comprueba si el usuario logado es el Super Administrador autorizado.
     * Quita espacios y convierte a mayúsculas para evitar errores de formato.
     */
    private boolean isSuperAdmin(HttpSession session) {
        if (session == null) return false;
        String usuario = (String) session.getAttribute("usuario");
        if (usuario == null) return false;
        return SUPER_USER_ID.equals(usuario.trim().toUpperCase());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        HttpSession session = request.getSession(false);
        if (!isAdmin(session)) { response.setStatus(HttpServletResponse.SC_FORBIDDEN); return; }

        String supervisor = request.getParameter("supervisor");
        String zona = request.getParameter("zona");
        List<Tienda> tiendas = new ArrayList<>();
        // SELECT actualizado para incluir la columna Supervisor
        StringBuilder sql = new StringBuilder("SELECT codigo, denominacion, Direccion, Lat, Lon, cp, Poblacion, Cadena, disponible, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, SqlRowUUID, Supervisor FROM tiendas WHERE 1=1");
        
        if (supervisor != null && !supervisor.isEmpty()) sql.append(" AND Supervisor = ?");
        if (zona != null && !zona.isEmpty()) sql.append(" AND Zona = ?");
        sql.append(" ORDER BY denominacion");

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (supervisor != null && !supervisor.isEmpty()) stmt.setString(idx++, supervisor);
            if (zona != null && !zona.isEmpty()) stmt.setString(idx++, zona);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Tienda t = new Tienda();
                    t.codigo = rs.getInt("codigo");
                    t.denominacion = rs.getString("denominacion");
                    t.direccion = rs.getString("Direccion");
                    t.lat = rs.getString("Lat"); t.lon = rs.getString("Lon");
                    t.cp = rs.getString("cp"); t.poblacion = rs.getString("Poblacion");
                    t.cadena = rs.getString("Cadena"); t.disponible = rs.getString("disponible");
                    t.prioridad = rs.getInt("prioridad");
                    t.huecosTurno1 = rs.getInt("HuecosTurno1"); t.huecosTurno2 = rs.getInt("HuecosTurno2");
                    t.huecosTurno3 = rs.getInt("HuecosTurno3"); t.huecosTurno4 = rs.getInt("HuecosTurno4");
                    t.sqlRowUUID = rs.getString("SqlRowUUID");
                    t.supervisor = rs.getString("Supervisor"); // Mapeo del supervisor
                    tiendas.add(t);
                }
            }
            mapper.writeValue(response.getWriter(), tiendas);
        } catch (SQLException e) { 
            logger.error("Error al listar tiendas", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); 
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);
        
        if (!isAdmin(session)) { 
            jsonResponse.put("success", false); 
            jsonResponse.put("message", "Acceso denegado.");
            mapper.writeValue(response.getWriter(), jsonResponse); 
            return; 
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        try {
            if ("refresh".equals(action)) {
                handleRefreshIndividual(request, jsonResponse, adminUser);
            } else if ("refreshAll".equals(action)) {
                if (!isSuperAdmin(session)) {
                    jsonResponse.put("success", false);
                    jsonResponse.put("message", "Acción restringida al Super Administrador.");
                } else {
                    handleRefreshAll(jsonResponse, adminUser);
                }
            } else if ("save".equals(action)) {
                handleSave(request, jsonResponse, session, adminUser);
            } else {
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Acción no reconocida.");
            }
        } catch (Exception e) {
            logger.error("Error procesando acción: " + action, e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error interno: " + e.getMessage());
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    /**
     * Sincronización global: SP -> SQL
     * Crea nuevas, actualiza existentes y desactiva las no encontradas en SP.
     */
    private void handleRefreshAll(Map<String, Object> jsonResponse, String adminUser) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SharePointUtil.LIST_NAME_TIENDAS);
        if (listId == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "No se encontró la lista 'Tiendas' en SharePoint.");
            return;
        }

        ListItemCollectionResponse spResponse = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
        if (spResponse == null || spResponse.getValue() == null) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error al recuperar datos de SharePoint.");
            return;
        }

        Set<Integer> codesFromSP = new HashSet<>();
        int creadas = 0;
        int actualizadas = 0;
        int desactivadas = 0;

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            for (ListItem item : spResponse.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                Object codigoObj = fields.get("codigo");
                if (codigoObj == null) continue;

                int codigo = parseToInt(codigoObj);
                codesFromSP.add(codigo);

                boolean exists = false;
                try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM tiendas WHERE codigo = ?")) {
                    check.setInt(1, codigo);
                    try (ResultSet rs = check.executeQuery()) { exists = rs.next(); }
                }

                String sql;
                if (exists) {
                    sql = "UPDATE tiendas SET denominacion=?, Direccion=?, cp=?, Poblacion=?, Cadena=?, disponible=?, Lat=?, Lon=?, prioridad=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, SqlRowUUID=?, Supervisor=?, notificar='S' WHERE codigo=?";
                    actualizadas++;
                } else {
                    sql = "INSERT INTO tiendas (denominacion, Direccion, cp, Poblacion, Cadena, disponible, Lat, Lon, prioridad, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, SqlRowUUID, Supervisor, notificar, codigo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S', ?)";
                    creadas++;
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, String.valueOf(fields.getOrDefault("denominacion", "")));
                    stmt.setString(2, String.valueOf(fields.getOrDefault("direccion", "")));
                    stmt.setString(3, String.valueOf(fields.getOrDefault("cp", "")));
                    stmt.setString(4, String.valueOf(fields.getOrDefault("poblacion", "")));
                    stmt.setString(5, String.valueOf(fields.getOrDefault("cadena", "")));
                    Object disp = fields.get("disponible");
                    stmt.setString(6, (disp instanceof Boolean && (Boolean)disp) ? "S" : "N");
                    stmt.setString(7, String.valueOf(fields.getOrDefault("lat", "0")));
                    stmt.setString(8, String.valueOf(fields.getOrDefault("lon", "0")));
                    stmt.setInt(9, parseToInt(fields.get("prioridad")));
                    stmt.setInt(10, parseToInt(fields.get("huecosTurno1")));
                    stmt.setInt(11, parseToInt(fields.get("huecosTurno2")));
                    stmt.setInt(12, parseToInt(fields.get("huecosTurno3")));
                    stmt.setInt(13, parseToInt(fields.get("huecosTurno4")));
                    stmt.setString(14, String.valueOf(fields.getOrDefault("SqlRowUUID", "")));
                    stmt.setString(15, String.valueOf(fields.getOrDefault("supervisor", ""))); // Sincronizar Supervisor
                    stmt.setInt(16, codigo);
                    stmt.executeUpdate();
                }
            }

            // Desactivar las que no están en SharePoint
            List<Integer> allLocalCodes = new ArrayList<>();
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT codigo FROM tiendas")) {
                while(rs.next()) allLocalCodes.add(rs.getInt(1));
            }
            
            for(Integer localCode : allLocalCodes) {
                if(!codesFromSP.contains(localCode)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE tiendas SET disponible='N', notificar='S' WHERE codigo=?")) {
                        ps.setInt(1, localCode);
                        ps.executeUpdate();
                        desactivadas++;
                    }
                }
            }

            // REGISTRO EN EL LOG: Antes del commit para que se guarde en la misma transacción
            String logMsg = String.format("Sincronización masiva SP finalizada. Creadas: %d, Actualizadas: %d, Desactivadas: %d", creadas, actualizadas, desactivadas);
            LogUtil.logOperation(conn, "SYNC_ALL_T", adminUser, logMsg);

            conn.commit();
            
            jsonResponse.put("success", true);
            jsonResponse.put("message", String.format("Sincronización completada: %d nuevas, %d actualizadas y %d desactivadas.", creadas, actualizadas, desactivadas));
        }
    }

    /**
     * Refresco individual: SP -> SQL
     * Si no se encuentra en SharePoint, se marca localmente como 'N' (No disponible).
     */
    private void handleRefreshIndividual(HttpServletRequest request, Map<String, Object> jsonResponse, String adminUser) throws Exception {
        String codigo = request.getParameter("codigo");
        Map<String, Object> spData = SharePointUtil.getTiendaFromSP(codigo);
        
        if (spData == null) {
            String sql = "UPDATE tiendas SET disponible='N', notificar='S' WHERE codigo=?";
            try (Connection conn = DatabaseUtil.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, Integer.parseInt(codigo));
                int rows = stmt.executeUpdate();
                
                if (rows > 0) {
                    LogUtil.logOperation(conn, "REFRESH_T_MISSING", adminUser, "Tienda " + codigo + " no encontrada en SharePoint. Marcada como 'No disponible'.");
                    jsonResponse.put("success", true);
                    jsonResponse.put("message", "La tienda no existe en SharePoint. Se ha marcado como 'No disponible' localmente.");
                } else {
                    jsonResponse.put("success", false);
                    jsonResponse.put("message", "No se encontró la tienda ni en SharePoint ni en el sistema local.");
                }
            }
            return;
        }

        // Consulta SQL incluyendo Supervisor
        String sql = "UPDATE tiendas SET denominacion=?, Direccion=?, cp=?, Poblacion=?, Cadena=?, disponible=?, Lat=?, Lon=?, prioridad=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, SqlRowUUID=?, Supervisor=?, notificar='S' WHERE codigo=?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, String.valueOf(spData.getOrDefault("denominacion", "")));
            stmt.setString(2, String.valueOf(spData.getOrDefault("direccion", "")));
            stmt.setString(3, String.valueOf(spData.getOrDefault("cp", "")));
            stmt.setString(4, String.valueOf(spData.getOrDefault("poblacion", "")));
            stmt.setString(5, String.valueOf(spData.getOrDefault("cadena", "")));
            Object disp = spData.get("disponible");
            stmt.setString(6, (disp instanceof Boolean && (Boolean)disp) ? "S" : "N");
            stmt.setString(7, String.valueOf(spData.getOrDefault("lat", "0")));
            stmt.setString(8, String.valueOf(spData.getOrDefault("lon", "0")));
            stmt.setInt(9, parseToInt(spData.get("prioridad")));
            stmt.setInt(10, parseToInt(spData.get("huecosTurno1")));
            stmt.setInt(11, parseToInt(spData.get("huecosTurno2")));
            stmt.setInt(12, parseToInt(spData.get("huecosTurno3")));
            stmt.setInt(13, parseToInt(spData.get("huecosTurno4")));
            stmt.setString(14, String.valueOf(spData.getOrDefault("SqlRowUUID", "")));
            stmt.setString(15, String.valueOf(spData.getOrDefault("supervisor", ""))); // Sincronizar Supervisor
            stmt.setInt(16, Integer.parseInt(codigo));

            int rows = stmt.executeUpdate();
            jsonResponse.put("success", rows > 0);
            jsonResponse.put("message", "Tienda " + codigo + " actualizada desde SharePoint.");
            if (rows > 0) LogUtil.logOperation(conn, "REFRESH_T", adminUser, "Refrescada tienda individual: " + codigo);
        }
    }

    private void handleSave(HttpServletRequest request, Map<String, Object> jsonResponse, HttpSession session, String adminUser) throws SQLException {
        boolean isUpdate = Boolean.parseBoolean(request.getParameter("isUpdate"));
        
        if (!isUpdate && !isSuperAdmin(session)) {
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Solo el Super Administrador puede dar de alta tiendas nuevas.");
            return;
        }

        int codigo = Integer.parseInt(request.getParameter("codigo"));
        
        String sql = isUpdate 
            ? "UPDATE tiendas SET disponible=?, HuecosTurno1=?, HuecosTurno2=?, HuecosTurno3=?, HuecosTurno4=?, notificar='S' WHERE codigo=?"
            : "INSERT INTO tiendas (disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, notificar, codigo) VALUES (?, ?, ?, ?, ?, 'S', ?)";
        
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, request.getParameter("disponible"));
            stmt.setInt(2, Integer.parseInt(request.getParameter("huecosTurno1")));
            stmt.setInt(3, Integer.parseInt(request.getParameter("huecosTurno2")));
            stmt.setInt(4, Integer.parseInt(request.getParameter("huecosTurno3")));
            stmt.setInt(5, Integer.parseInt(request.getParameter("huecosTurno4")));
            stmt.setInt(6, codigo);
            
            int rows = stmt.executeUpdate();
            jsonResponse.put("success", rows > 0);
            if (rows > 0) LogUtil.logOperation(conn, isUpdate ? "SAVE_T" : "CREATE_T", adminUser, (isUpdate ? "Actualizada" : "Creada") + " tienda local: " + codigo);
        }
    }

    private int parseToInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { 
            return Integer.parseInt(String.valueOf(obj).trim()); 
        } catch (Exception e) { return 0; }
    }
}