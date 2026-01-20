package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.microsoft.graph.models.FieldValueSet;

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
    private static final long serialVersionUID = 8L; // VersiÃ³n actualizada

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
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesiÃ³n de usuario activa.");
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
            jsonResponse = "{\"success\": true, \"message\": \"Â¡Turnos guardados y sincronizados con Ã©xito!\"}";

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse = "{\"success\": false, \"message\": \"Error de base de datos. Los cambios no se guardaron.\"}";
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse = "{\"success\": false, \"message\": \"Error en los datos enviados: " + e.getMessage() + "\"}";
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

            stmt.executeUpdate();
            String logComment = isAdmin ? "Admin " + usuarioEnSesion + " modificÃ³ los turnos de " + usuarioFinal : "Guardado/ModificaciÃ³n de turnos para la campaÃ±a " + campanaId;
            LogUtil.logOperation(conn, "ASIGNACION", usuarioEnSesion, logComment);
        }
    }

    private void replicarAsignacionASharePoint(Connection conn, String campanaId, String usuario) throws Exception {
        String listNameAsignaciones = "Asignaciones";
        String listNameVoluntarios = "Voluntarios";
        String listNameTiendas = "Tiendas";

        String listIdAsignaciones = SharepointUtil.getListId(SharepointUtil.SITE_ID, listNameAsignaciones);
        String listIdVoluntarios = SharepointUtil.getListId(SharepointUtil.SITE_ID, listNameVoluntarios);
        String listIdTiendas = SharepointUtil.getListId(SharepointUtil.SITE_ID, listNameTiendas);

        if (listIdAsignaciones == null || listIdVoluntarios == null || listIdTiendas == null) {
            throw new Exception("Una o mÃ¡s listas de SharePoint no se encontraron (Asignaciones, Voluntarios, Tiendas).");
        }

        String voluntarioUuid = getSqlRowUuid(conn, usuario);
        if (voluntarioUuid == null) {
             System.err.println("ADVERTENCIA: No se pudo encontrar el SqlRowUUID para el usuario '" + usuario + "'. No se puede replicar la asignaciÃ³n.");
            return;
        }
        
        String spVoluntarioId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listIdVoluntarios, "SqlRowUUID", voluntarioUuid);
        if (spVoluntarioId == null) {
            throw new Exception("El voluntario con UUID '" + voluntarioUuid + "' no fue encontrado en SharePoint.");
        }

        String assignmentUuid = "AS-" + voluntarioUuid; // Usamos el UUID del voluntario para el tÃ­tulo
        
        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("Title", assignmentUuid);
        fields.getAdditionalData().put("UsuarioLookupId", spVoluntarioId);
        fields.getAdditionalData().put("Campana", campanaId);
        
        String sqlSelect = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Usuario = ? AND Campana = ?";
        boolean tieneTurnos = false;
        try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
            stmt.setString(1, usuario);
            stmt.setString(2, campanaId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                for (int i = 1; i <= 4; i++) {
                    int idTiendaDb = rs.getInt("Turno" + i);
                    String comentario = rs.getString("Comentario" + i);

                    fields.getAdditionalData().put("Turno" + i + "LookupId", null); // Limpiar por defecto
                    fields.getAdditionalData().put("Comentario" + i, comentario);

                    if (idTiendaDb > 0) {
                        tieneTurnos = true;
                        String tiendaUuid = getSqlRowUuidForTienda(conn, idTiendaDb);
                        if (tiendaUuid != null) {
                            String spTiendaId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listIdTiendas, "SqlRowUUID", tiendaUuid);
                            if (spTiendaId != null) {
                                fields.getAdditionalData().put("Turno" + i + "LookupId", spTiendaId);
                            }
                        }
                    }
                }
            }
        }

        String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SITE_ID, listIdAsignaciones, "Title", assignmentUuid);

        if (itemId != null) { // El item existe
            if (tieneTurnos) {
                SharepointUtil.updateListItem(SharepointUtil.SITE_ID, listIdAsignaciones, itemId, fields);
            } else {
                SharepointUtil.deleteListItem(SharepointUtil.SITE_ID, listIdAsignaciones, itemId);
            }
        } else { // El item no existe
            if (tieneTurnos) {
                SharepointUtil.createListItem(SharepointUtil.SITE_ID, listIdAsignaciones, fields);
            }
        }
        LogUtil.logOperation(conn, "REPLICATE_ASIGNACION", usuario, "AsignaciÃ³n replicada a SharePoint para campaÃ±a " + campanaId);
    }

    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("SqlRowUUID");
                }
            }
        }
        return null;
    }

    private String getSqlRowUuidForTienda(Connection conn, int codigoTienda) throws SQLException {
        String sql = "SELECT SqlRowUUID FROM tiendas WHERE codigo = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, codigoTienda);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("SqlRowUUID");
                }
            }
        }
        return null;
    }
}

