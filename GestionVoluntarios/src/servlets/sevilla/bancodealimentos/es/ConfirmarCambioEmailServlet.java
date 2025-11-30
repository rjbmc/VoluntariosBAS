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

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/confirmar-cambio-email")
public class ConfirmarCambioEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        String jsonResponse;

        String token = request.getParameter("token");

        if (token == null || token.trim().isEmpty()) {
            sendError(response, "Token no proporcionado.");
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            String usuario = null;
            String nuevoEmail = null;
            String sqlRowUuid = null;

            String sqlFindUser = "SELECT Usuario, nuevo_email, SqlRowUUID FROM voluntarios WHERE token_cambio_email = ?";
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        nuevoEmail = rs.getString("nuevo_email");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (usuario != null && nuevoEmail != null) {
                String sqlUpdate = "UPDATE voluntarios SET Email = ?, nuevo_email = NULL, token_cambio_email = NULL, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, nuevoEmail);
                    stmtUpdate.setString(2, usuario);
                    stmtUpdate.executeUpdate();
                }
                
                LogUtil.logOperation(conn, "CAMBIO_EMAIL_OK", usuario, "Email actualizado en BBDD a: " + nuevoEmail);

                // --- INICIO DE LA CORRECCIÓN ---
                if (sqlRowUuid != null) {
                    try {
                        Map<String, Object> spData = new HashMap<>();
                        spData.put("field_6", nuevoEmail); // field_6 es el email en SharePoint

                        SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                        LogUtil.logOperation(conn, "REPLICATE_SUCCESS", usuario, "Email actualizado en SharePoint a: " + nuevoEmail);
                    } catch (Exception e) {
                        System.err.println("ADVERTENCIA: Fallo al replicar a SharePoint el cambio de email para " + usuario + ". Causa: " + e.getMessage());
                        LogUtil.logOperation(conn, "REPLICATE_ERROR", usuario, "Fallo al replicar cambio de email a SharePoint: " + e.getMessage());
                    }
                } else {
                    LogUtil.logOperation(conn, "REPLICATE_WARNING", usuario, "No se encontró SqlRowUUID para replicar el cambio de email a SharePoint.");
                }
                // --- FIN DE LA CORRECCIÓN ---

                conn.commit();
                jsonResponse = "{\"success\": true, \"message\": \"Tu dirección de correo ha sido actualizada con éxito.\"}";
            } else {
                conn.rollback();
                jsonResponse = "{\"success\": false, \"message\": \"El enlace de confirmación no es válido o ya ha sido utilizado.\"}";
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            if (e.getErrorCode() == 1062) {
                 jsonResponse = "{\"success\": false, \"message\": \"La nueva dirección de correo ya está en uso por otro voluntario.\"}";
            } else {
                 jsonResponse = "{\"success\": false, \"message\": \"Error de base de datos al actualizar el email.\"}";
            }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } 

        out.print(jsonResponse);
        out.flush();
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getWriter().print("{\"success\": false, \"message\": \"" + message + "\"}");
    }
}
