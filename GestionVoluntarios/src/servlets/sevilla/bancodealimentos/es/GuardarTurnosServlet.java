package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/guardar-turnos")
public class GuardarTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 5L; // Versión actualizada

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return isAdminAttr != null && (boolean) isAdminAttr;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        String jsonResponse;

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }

        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioAGuardar = request.getParameter("usuario");
        String usuarioFinal = (isAdmin(session) && usuarioAGuardar != null && !usuarioAGuardar.trim().isEmpty()) ? usuarioAGuardar : usuarioEnSesion;
        
        String campanaId = request.getParameter("campanaId");

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            guardarTurnosEnDB(conn, request, campanaId, usuarioFinal, isAdmin(session), usuarioEnSesion);
            replicarAsignacionASharePoint(conn, campanaId, usuarioFinal);

            conn.commit();
            jsonResponse = "{\"success\": true, \"message\": \"¡Turnos guardados y sincronizados con éxito!\"}";

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse = "{\"success\": false, \"message\": \"Error de base de datos. Los cambios no se guardaron.\"}";
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse = "{\"success\": false, \"message\": \"Error en los datos enviados.\"}";
        }

        out.print(jsonResponse);
        out.flush();
    }

    private void guardarTurnosEnDB(Connection conn, HttpServletRequest request, String campanaId, String usuarioFinal, boolean isAdmin, String usuarioEnSesion) throws SQLException, ServletException {
        String sql = "INSERT INTO voluntarios_en_campana (Campana, Usuario, Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4, notificar) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "Turno1 = VALUES(Turno1), Comentario1 = VALUES(Comentario1), " +
                     "Turno2 = VALUES(Turno2), Comentario2 = VALUES(Comentario2), " +
                     "Turno3 = VALUES(Turno3), Comentario3 = VALUES(Comentario3), " +
                     "Turno4 = VALUES(Turno4), Comentario4 = VALUES(Comentario4), " +
                     "notificar = VALUES(notificar)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId);
            stmt.setString(2, usuarioFinal);
            
            Integer[] tiendas = new Integer[4];
            String[] comentarios = new String[4];

            for (int i = 1; i <= 4; i++) {
                String tiendaStr = request.getParameter("tienda_" + i);
                if (tiendaStr != null && !tiendaStr.isEmpty()) {
                    tiendas[i - 1] = Integer.parseInt(tiendaStr);
                    comentarios[i - 1] = request.getParameter("comentario_" + i);
                } else {
                    tiendas[i - 1] = null;
                    comentarios[i - 1] = "";
                }
            }

            stmt.setObject(3, tiendas[0]);
            stmt.setString(4, comentarios[0]);
            stmt.setObject(5, tiendas[1]);
            stmt.setString(6, comentarios[1]);
            stmt.setObject(7, tiendas[2]);
            stmt.setString(8, comentarios[2]);
            stmt.setObject(9, tiendas[3]);
            stmt.setString(10, comentarios[3]);
            stmt.setString(11, "S");

            int filasAfectadas = stmt.executeUpdate();
            if (filasAfectadas > 0) {
                String logComment = isAdmin ? "Admin " + usuarioEnSesion + " modificó los turnos de " + usuarioFinal : "Guardado/Modificación de turnos para la campaña " + campanaId;
                LogUtil.logOperation(conn, "ASIGNACION", usuarioEnSesion, logComment);
            }
        }
    }

    private void replicarAsignacionASharePoint(Connection conn, String campanaId, String usuario) throws Exception {
        String listName = "Asignaciones" + campanaId;
        String voluntarioUuid = getSqlRowUuid(conn, usuario);
        if (voluntarioUuid == null) {
             System.err.println("ADVERTENCIA: No se pudo encontrar el SqlRowUUID para el usuario '" + usuario + "'. No se puede replicar la asignación.");
            return;
        }
        
        String assignmentUuid = "AS-" + voluntarioUuid;
        Map<String, Object> data = new HashMap<>();
        data.put("Title", assignmentUuid);
        data.put("VoluntarioLookup", voluntarioUuid);
        
        String sqlSelect = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
            stmt.setString(1, usuario);
            stmt.setString(2, campanaId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                data.put("Turno1", rs.getObject("Turno1"));
                data.put("Comentario1", rs.getString("Comentario1"));
                data.put("Turno2", rs.getObject("Turno2"));
                data.put("Comentario2", rs.getString("Comentario2"));
                data.put("Turno3", rs.getObject("Turno3"));
                data.put("Comentario3", rs.getString("Comentario3"));
                data.put("Turno4", rs.getObject("Turno4"));
                data.put("Comentario4", rs.getString("Comentario4"));
            }
        }

        boolean tieneTurnos = data.get("Turno1") != null || data.get("Turno2") != null || data.get("Turno3") != null || data.get("Turno4") != null;

        SharepointReplicationUtil.Operation operation;
        String uuidForOperation;
        String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listName, "Title", assignmentUuid);

        if (itemId != null) { 
            if (tieneTurnos) {
                operation = SharepointReplicationUtil.Operation.UPDATE;
                uuidForOperation = itemId;
            } else {
                operation = SharepointReplicationUtil.Operation.DELETE;
                uuidForOperation = itemId;
            }
        } else { 
            if (tieneTurnos) {
                operation = SharepointReplicationUtil.Operation.INSERT;
                uuidForOperation = assignmentUuid;
            } else {
                return; 
            }
        }
        
        SharepointReplicationUtil.replicate(conn, SharepointUtil.SITE_ID, listName, data, operation, uuidForOperation);
        LogUtil.logOperation(conn, "REPLICATE_ASIGNACION", usuario, "Asignación replicada a SharePoint para campaña " + campanaId + " con operación " + operation.name());
    }

    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException, ServletException {
        String uuid = null;
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    uuid = rs.getString("SqlRowUUID");
                }
            }
        }
        if (uuid == null) {
            LogUtil.logOperation(conn, "REPLICATE_ERROR", "SYSTEM", "No se encontró SqlRowUUID para el usuario: " + usuario);
        }
        return uuid;
    }
}
